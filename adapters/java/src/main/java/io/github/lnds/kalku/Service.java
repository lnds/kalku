package io.github.lnds.kalku;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answering requests.
 *
 * <p>One request per line in, one reply per line out, and nothing else may reach the output:
 * the kaikai side banishes a kalku for a line it cannot read.
 *
 * <p>What this kalku cannot do it says, in a reply, so a request is never met with silence.
 * And a cast that produced no verdict is never given one: it is an error that ends this
 * kalku, which the kaikai side records as {@code crashed}, with the reason.
 */
final class Service {
  // Directories whose sources are the suite, by the conventions of Maven and Gradle.
  private static final List<String> TEST_DIRS =
      Arrays.asList("test", "tests", "integrationTest", "testFixtures", "androidTest");

  // What a build or a JVM needs of the environment this kalku was summoned in. Nothing else
  // of it is handed on; what the run adds comes in `hello`.
  private static final List<String> INHERITED =
      Arrays.asList(
          "PATH", "HOME", "USER", "LANG", "LC_ALL", "TMPDIR", "MAVEN_OPTS", "MAVEN_ARGS",
          "KALKU_MAVEN");

  private final InputStream in;
  private final OutputStream out;
  private final String adapter;
  private Protocol.Request hello;

  // What `prepare` leaves for every request after it.
  private Maven.Build build;
  private Path work;
  private Path runner;
  private final Set<String> tests = new LinkedHashSet<>();
  // What the compiler says of a file compiled the way a cast compiles it, before any change:
  // nothing when it compiles. Asked once for each file, and once for the whole project.
  private final Map<String, Compiler.Result> unchanged = new HashMap<>();

  Service(InputStream in, OutputStream out, String adapter) {
    this.in = in;
    this.out = out;
    this.adapter = adapter;
  }

  /** Serves until `shutdown` or the end of input. */
  void serve() throws IOException {
    while (true) {
      String line = Framing.readLine(in, Protocol.MAX_LINE);
      if (line == null) {
        return;
      }
      boolean stop = handle(line);
      if (stop) {
        return;
      }
    }
  }

  private void say(String reply) throws IOException {
    out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  // One line in, its reply out, and whether to leave.
  private boolean handle(String line) throws IOException {
    if (line == Framing.TOO_LONG) {
      say(refuse(null, Protocol.LINE_TOO_LONG, "a line longer than " + Protocol.MAX_LINE + " bytes"));
      return false;
    }
    Protocol.Request request;
    try {
      request = Protocol.decode(line);
    } catch (Protocol.DecodeError e) {
      say(refuse(e.id, e.kind, e.detail));
      return false;
    }
    switch (request.type) {
      case "hello":
        say(greet(request));
        return false;
      case "shutdown":
        say(Protocol.bye(request.id));
        return true;
      case "abort":
        // A cast is answered before the next line is read, so none is ever running here.
        say(Protocol.aborted(request.id, request.cast, true));
        return false;
      default:
        break;
    }
    if (hello == null) {
      say(Protocol.error(request.id, "not_ready", "`hello` has to come first", false));
      return false;
    }
    switch (request.type) {
      case "sites":
        say(sites(request));
        break;
      case "prepare":
        say(prepare(request));
        break;
      case "baseline":
        say(baseline(request));
        break;
      case "cast":
        say(cast(request));
        break;
      default:
        say(
            Protocol.error(
                request.id,
                "not_implemented",
                "this kalku does not answer `" + request.type + "`",
                true));
    }
    return false;
  }

  private static String refuse(Long id, String kind, String detail) {
    return Protocol.error(id == null ? 0 : id, "bad_request", kind + ": " + detail, false);
  }

  private static long ms(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }

  // ---- hello ---------------------------------------------------------------

  private String greet(Protocol.Request request) {
    if (request.protocol != Protocol.VERSION) {
      return Protocol.error(
          request.id,
          "protocol_mismatch",
          "kalku speaks protocol " + Protocol.VERSION + ", kaikai side speaks " + request.protocol,
          true);
    }
    if (!compilerPresent()) {
      return Protocol.error(
          request.id,
          "toolchain_missing",
          "this Java runtime ("
              + System.getProperty("java.home")
              + ") has no compiler; kalku reads sources with the project's own javac, so it "
              + "needs a JDK, not a JRE",
          true);
    }
    hello = request;
    return Protocol.ready(
        request.id, adapter, runtime(), Arrays.asList("cast", "recompile_dependents"));
  }

  // A runtime cut down to less than a JDK may lack the compiler's classes altogether, and then
  // even asking for it fails to link.
  private static boolean compilerPresent() {
    try {
      return Sites.compilerPresent();
    } catch (LinkageError e) {
      return false;
    }
  }

  private static String runtime() {
    String vendor = System.getProperty("java.vendor", "");
    String version = System.getProperty("java.version", "?");
    return vendor.isEmpty() ? "Java " + version : "Java " + version + " (" + vendor + ")";
  }

  // The environment of everything this kalku starts: a short list of what a build needs, the
  // JDK this kalku itself runs on, and what the run asked for.
  private Map<String, String> environment() {
    Map<String, String> env = new LinkedHashMap<>();
    for (String name : INHERITED) {
      String value = System.getenv(name);
      if (value != null) {
        env.put(name, value);
      }
    }
    env.put("JAVA_HOME", System.getProperty("java.home"));
    env.putAll(hello.env);
    return env;
  }

  // ---- sites ---------------------------------------------------------------

  private String sites(Protocol.Request request) {
    List<Map<String, Object>> found = new ArrayList<>();
    List<Map<String, Object>> skipped = new ArrayList<>();
    for (String file : request.files) {
      String[] why = search(file, request, found);
      if (why != null) {
        skipped.add(Protocol.skipped(file, why[0], why[1]));
      }
    }
    return Protocol.sitesFound(request.id, found, skipped);
  }

  // The reason and the message a file was skipped with, or null when it was searched.
  private String[] search(String file, Protocol.Request request, List<Map<String, Object>> found) {
    if (!file.endsWith(".java")) {
      return new String[] {"not_java", "`" + file + "` is not a Java source file"};
    }
    if (isTestFile(file)) {
      return new String[] {
        "test_file", "`" + file + "` is part of the suite, which is not measured"
      };
    }
    Source src;
    try {
      Path root = Paths.get(hello.root);
      src = Source.read(root.resolve(file));
    } catch (IOException | InvalidPathException e) {
      return new String[] {"unreadable", "cannot read `" + file + "`: " + e};
    }
    try {
      for (Sites.Site site :
          Sites.find(file, src.text, new HashSet<>(request.spells), request.excludeCalls)) {
        found.add(Protocol.site(site));
      }
      return null;
    } catch (Sites.ParseError e) {
      return new String[] {"parse_error", e.getMessage()};
    }
  }

  /**
   * True when a path is part of the suite, which is the oracle and is never measured: under a
   * test source directory, or named the way a test class is.
   */
  static boolean isTestFile(String relative) {
    String[] parts = relative.replace('\\', '/').split("/");
    for (int i = 0; i < parts.length - 1; i++) {
      if (TEST_DIRS.contains(parts[i])) {
        return true;
      }
    }
    String name = parts[parts.length - 1];
    return name.endsWith("Test.java") || name.endsWith("Tests.java") || name.endsWith("IT.java");
  }

  // ---- prepare -------------------------------------------------------------

  // The project is copied into the reni and built there, by its own build tool; the runner
  // is compiled against the JUnit it found; and the tests are listed. A kalku in a pool is
  // only ever asked to cast, so everything a cast needs is left by this.
  private String prepare(Protocol.Request request) throws IOException {
    long started = System.nanoTime();
    build = null;
    tests.clear();
    unchanged.clear();
    Path reni = Paths.get(hello.reni);
    work = reni.resolve("work").resolve(String.valueOf(hello.worker));
    Path project = work.resolve("project");
    try {
      int changed = Project.sync(Paths.get(hello.root), project, reni);
      // Maven names its directories by where they really are, past any link on the way.
      project = project.toRealPath();
      work = project.getParent();
      if (!Files.isRegularFile(project.resolve("pom.xml"))) {
        throw new Maven.Failed(
            "no `pom.xml` at the root of the project: only Maven projects are measured yet");
      }
      Maven.Build built = Maven.build(project, work.resolve("lib"), environment(), changed > 0);
      runner = work.resolve("runner");
      String refused = Child.compileRunner(built, runner);
      if (refused != null) {
        throw new Maven.Failed(
            "the test runner does not compile against JUnit Platform "
                + built.platform
                + ": "
                + refused);
      }
      Child.Ran listed = run(built, Collections.emptyList(), "discover", Collections.emptyList());
      if (listed.problem() != null) {
        throw new Maven.Failed("the tests could not be listed: " + listed.problem());
      }
      for (Map<?, ?> found : listed.of("found")) {
        tests.add((String) found.get("id"));
      }
      build = built;
      long modules = Project.sources(Collections.singletonList(built.sources)).size();
      return Protocol.prepared(request.id, ms(started), modules);
    } catch (Maven.Failed e) {
      return Protocol.error(request.id, "prepare_failed", e.getMessage(), true);
    }
  }

  private Child.Ran run(Maven.Build built, List<Path> before, String mode, List<String> ids)
      throws IOException {
    return Child.run(built, runner, before, mode, ids, work.resolve("run"), environment());
  }

  // ---- baseline ------------------------------------------------------------

  private String baseline(Protocol.Request request) throws IOException {
    if (build == null) {
      return Protocol.error(request.id, "not_ready", "`prepare` has to come first", false);
    }
    long started = System.nanoTime();
    Child.Ran ran = run(build, Collections.emptyList(), "run", Collections.emptyList());
    if (ran.problem() != null) {
      return Protocol.error(request.id, "baseline_failed", ran.problem(), true);
    }
    List<Map<String, Object>> ranTests = new ArrayList<>();
    List<Map<String, Object>> failures = new ArrayList<>();
    for (Map<?, ?> event : ran.of("test")) {
      String id = (String) event.get("id");
      String outcome = (String) event.get("outcome");
      // A test that was skipped, or that gave up on an assumption, looked at nothing.
      if (!outcome.equals("passed") && !outcome.equals("failed")) {
        continue;
      }
      tests.add(id);
      Map<String, Object> test = new LinkedHashMap<>();
      test.put("test", id);
      test.put("file", fileOf(id));
      test.put("duration_ms", event.get("ms"));
      ranTests.add(test);
      if (outcome.equals("failed")) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("test", id);
        failure.put("message", String.valueOf(event.get("message")));
        failures.add(failure);
      }
    }
    return Protocol.baselineDone(request.id, ms(started), ranTests, failures);
  }

  // The source a test is written in: `a.B$C#m()` is in `a/B.java` under the test sources.
  private String fileOf(String test) {
    String type = test.contains("#") ? test.substring(0, test.indexOf('#')) : test;
    String outer = type.contains("$") ? type.substring(0, type.indexOf('$')) : type;
    Path file = build.testSources.resolve(outer.replace('.', '/') + ".java");
    return build.project.relativize(file).toString().replace('\\', '/');
  }

  // ---- cast ----------------------------------------------------------------

  private String cast(Protocol.Request request) throws IOException {
    if (build == null) {
      return Protocol.error(request.id, "not_ready", "`prepare` has to come first", false);
    }
    for (String test : request.tests) {
      if (!tests.contains(test)) {
        return Protocol.error(
            request.id, "unknown_test", "`" + test + "` is not a test this kalku knows", false);
      }
    }
    // No test to run is no cast: the runner takes an empty choice for the whole suite, and a
    // test nobody chose would then be what killed.
    if (request.tests.isEmpty()) {
      return unjudged(request, "no test was selected for this wekufe");
    }
    long started = System.nanoTime();
    Path file = build.project.resolve(request.file).normalize();
    Source src;
    try {
      if (!file.startsWith(build.project)) {
        throw new IOException("it is outside the project");
      }
      src = Source.read(file);
    } catch (IOException | InvalidPathException e) {
      return Protocol.error(
          request.id, "bad_request", "cannot read `" + request.file + "` in the reni: " + e, false);
    }
    int from = src.index(request.startByte);
    int to = request.endByte < 0 ? -1 : src.index(request.endByte);
    // A site is only valid for the text it was found in.
    if (from < 0
        || to < from
        || (request.original != null && !src.slice(from, to).equals(request.original))) {
      return Protocol.error(
          request.id,
          "bad_request",
          "`" + request.file + "` is not the text this site was found in",
          false);
    }
    String wekufe = src.splice(from, to, request.replacement);

    List<Path> before = new ArrayList<>();
    Compiler.Result compiled =
        compile(file, wekufe, request.dependents, work.resolve("cast"), before);
    if (!compiled.ok()) {
      // What does not compile has to be the wekufe, not the way this kalku compiles.
      Compiler.Result control = unchanged(file, src.text, request.dependents);
      if (!control.ok()) {
        return unjudged(
            request,
            "kalku cannot compile `"
                + request.file
                + "` the way the project's build does, even unchanged, so no wekufe in it can "
                + "be judged: "
                + control.error);
      }
      return Protocol.castDone(
          request.id, request.wekufe, "compile_error", null, compiled.error, ms(started));
    }

    Child.Ran ran = run(build, before, "run", request.tests);
    if (ran.problem() != null) {
      return unjudged(request, ran.problem());
    }
    int passed = 0;
    for (Map<?, ?> event : ran.of("test")) {
      String id = (String) event.get("id");
      if ("failed".equals(event.get("outcome"))) {
        // What failed may be a class and not one of its tests: then no test is named.
        return Protocol.castDone(
            request.id, request.wekufe, "killed", tests.contains(id) ? id : null, null, ms(started));
      }
      if ("passed".equals(event.get("outcome")) && request.tests.contains(id)) {
        passed++;
      }
    }
    // Nothing ran, so nothing was measured. `survived` here would count a hole nobody looked
    // for as a hole somebody looked for and did not find.
    if (passed == 0) {
      return unjudged(
          request, "none of the " + request.tests.size() + " selected test(s) ran and passed");
    }
    return Protocol.castDone(request.id, request.wekufe, "survived", null, null, ms(started));
  }

  // No verdict. The kaikai side records a fatal error during a cast as `crashed`, never as a
  // kill, and summons another kalku.
  private static String unjudged(Protocol.Request request, String why) {
    return Protocol.error(request.id, "cast_failed", why, true);
  }

  // Compiles a cast's classes into `into`, and lists in `before` the directories that go
  // before the project's own.
  //
  // One file is compiled for most wekufe: every other class still fits it. A wekufe in a
  // constant is in every class the compiler copied the constant into, and that includes the
  // tests, so the whole project is compiled again, sources and tests.
  private Compiler.Result compile(
      Path file, String text, boolean dependents, Path into, List<Path> before)
      throws IOException {
    Project.clear(into);
    Path main = into.resolve("classes");
    List<Path> against = new ArrayList<>();
    against.add(build.classes);
    against.addAll(build.libraries);
    if (!dependents) {
      before.add(main);
      return Compiler.compile(
          Collections.singletonList(file), file, text, against, main, build.compilerFlags,
          build.project);
    }
    Compiler.Result sources =
        Compiler.compile(
            Project.sources(Collections.singletonList(build.sources)), file, text, against, main,
            build.compilerFlags, build.project);
    if (!sources.ok()) {
      return sources;
    }
    Path test = into.resolve("test-classes");
    List<Path> testsAgainst = new ArrayList<>();
    testsAgainst.add(main);
    testsAgainst.add(build.classes);
    testsAgainst.add(build.testClasses);
    testsAgainst.addAll(build.libraries);
    before.add(test);
    before.add(main);
    return Compiler.compile(
        Project.sources(Collections.singletonList(build.testSources)), null, null, testsAgainst,
        test, build.testCompilerFlags, build.project);
  }

  private Compiler.Result unchanged(Path file, String text, boolean dependents)
      throws IOException {
    String key = dependents ? "*" : file.toString();
    if (!unchanged.containsKey(key)) {
      unchanged.put(
          key, compile(file, text, dependents, work.resolve("control"), new ArrayList<>()));
    }
    return unchanged.get(key);
  }
}
