package io.github.lnds.kalku;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
          "KALKU_MAVEN", "GRADLE_OPTS", "GRADLE_USER_HOME", "KALKU_GRADLE");

  private final InputStream in;
  private final OutputStream out;
  private final String adapter;
  private Protocol.Request hello;

  // What `prepare` leaves for every request after it.
  private Maven.Reactor project;
  private Path work;
  // The runner as it was compiled for each module that has tests, against that module's JUnit.
  private final Map<Maven.Build, Path> runners = new HashMap<>();
  // Every test, and the module it is a test of.
  private final Map<String, Maven.Build> tests = new LinkedHashMap<>();
  // What the compiler says of a file compiled the way a cast compiles it, before any change:
  // nothing when it compiles. Asked once for each file, and once for the whole project.
  private final Map<String, Compiler.Result> unchanged = new HashMap<>();

  Service(InputStream in, OutputStream out, String adapter) {
    this.in = in;
    this.out = out;
    this.adapter = adapter;
  }

  // The lines of the channel, read by a thread of their own so that one can arrive while a
  // cast is running: `abort` is the one request that may. The end of the input is in the
  // queue too, as itself.
  private static final Object END = new Object();
  private final BlockingQueue<Object> arriving = new LinkedBlockingQueue<>();
  // Lines that arrived while a cast was running and were not its `abort`: served after it.
  private final Deque<Object> waiting = new ArrayDeque<>();
  private boolean ended;

  /** Serves until `shutdown` or the end of input. */
  void serve() throws IOException {
    Thread reader =
        new Thread(
            () -> {
              try {
                String line;
                while ((line = Framing.readLine(in, Protocol.MAX_LINE)) != null) {
                  arriving.add(line);
                }
              } catch (IOException e) {
                // A channel that cannot be read has ended.
              }
              arriving.add(END);
            },
            "kalku-channel");
    reader.setDaemon(true);
    reader.start();
    while (!ended) {
      Object line = waiting.isEmpty() ? take() : waiting.poll();
      if (line == END || handle((String) line)) {
        return;
      }
    }
  }

  private Object take() {
    try {
      return arriving.take();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return END;
    }
  }

  // Whether `abort` has been asked of this cast, in what has arrived so far or arrives within
  // `millis`. Anything else that arrived is kept for after the cast; the end of the input
  // ends the cast too, since nobody is left to hear its outcome.
  private Protocol.Request aborting(long cast, long millis) {
    try {
      Object line = millis > 0 ? arriving.poll(millis, TimeUnit.MILLISECONDS) : arriving.poll();
      while (line != null) {
        if (line == END) {
          ended = true;
          return null;
        }
        Protocol.Request asked = abortOf((String) line, cast);
        if (asked != null) {
          return asked;
        }
        waiting.add(line);
        line = arriving.poll();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      ended = true;
    }
    return null;
  }

  private static Protocol.Request abortOf(String line, long cast) {
    if (line == Framing.TOO_LONG) {
      return null;
    }
    try {
      Protocol.Request request = Protocol.decode(line);
      return request.type.equals("abort") && request.cast == cast ? request : null;
    } catch (Protocol.DecodeError e) {
      return null;
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
        // An `abort` that is read here found no cast running: the one it names has already
        // been answered, and there is nothing that was not restored.
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
        String done = cast(request);
        // A cast that was aborted has been answered already, with `aborted`, and gets no
        // outcome of its own.
        if (done != null) {
          say(done);
        }
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
        request.id, adapter, runtime(), Arrays.asList("cast", "per_test_coverage", "recompile_dependents", "abort"));
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
  // is compiled against the JUnit each module found; and the tests are listed. A kalku in a
  // pool is only ever asked to cast, so everything a cast needs is left by this.
  private String prepare(Protocol.Request request) throws IOException {
    long started = System.nanoTime();
    project = null;
    tests.clear();
    runners.clear();
    unchanged.clear();
    Path reni = Paths.get(hello.reni);
    work = reni.resolve("work").resolve(String.valueOf(hello.worker));
    Path copy = work.resolve("project");
    try {
      int changed = Project.sync(Paths.get(hello.root), copy, reni);
      // Maven names its directories by where they really are, past any link on the way.
      copy = copy.toRealPath();
      work = copy.getParent();
      // A project that has both is built the way its `pom.xml` says.
      Maven.Reactor built;
      if (Files.isRegularFile(copy.resolve("pom.xml"))) {
        built = Maven.build(copy, work.resolve("lib"), environment(), changed > 0);
      } else if (Gradle.builds(copy)) {
        built = Gradle.build(copy, work, environment(), changed > 0);
      } else {
        throw new Maven.Failed(
            "neither a `pom.xml` nor a Gradle build at the root of the project: kalku measures "
                + "Java projects built by Maven or by Gradle");
      }
      long modules = 0;
      for (Maven.Build module : built.modules) {
        modules += Project.sources(Collections.singletonList(module.sources)).size();
        if (module.platform == null) {
          continue;
        }
        Path runner = work.resolve("runner").resolve(String.valueOf(built.modules.indexOf(module)));
        String refused = Child.compileRunner(module, runner);
        if (refused != null) {
          throw new Maven.Failed(
              "the test runner does not compile against JUnit Platform "
                  + module.platform
                  + ": "
                  + refused);
        }
        runners.put(module, runner);
        Child.Ran listed =
            Child.run(
                module, runner, Collections.emptyList(), "discover", Collections.emptyList(),
                work.resolve("run"), environment());
        if (listed.problem() != null) {
          throw new Maven.Failed("the tests could not be listed: " + listed.problem());
        }
        for (Map<?, ?> found : listed.of("found")) {
          tests.put(named(module, (String) found.get("id")), module);
        }
      }
      project = built;
      return Protocol.prepared(request.id, ms(started), modules);
    } catch (Maven.Failed e) {
      return Protocol.error(request.id, "prepare_failed", e.getMessage(), true);
    }
  }

  // A test's name says which module it is a test of, where there is more than one: the same
  // class name can be in two of them.
  private static String named(Maven.Build module, String test) {
    return module.name.isEmpty() ? test : module.name + "::" + test;
  }

  // The name a module's own runner knows a test by.
  private static String local(String test) {
    int at = test.indexOf("::");
    return at < 0 ? test : test.substring(at + 2);
  }

  // The modules that have tests, in the order Maven builds them.
  private List<Maven.Build> tested() {
    List<Maven.Build> out = new ArrayList<>();
    for (Maven.Build module : project.modules) {
      if (runners.containsKey(module)) {
        out.add(module);
      }
    }
    return out;
  }

  // ---- baseline ------------------------------------------------------------

  private String baseline(Protocol.Request request) throws IOException {
    if (project == null) {
      return Protocol.error(request.id, "not_ready", "`prepare` has to come first", false);
    }
    long started = System.nanoTime();
    Map<Maven.Build, Child.Ran> plain = new LinkedHashMap<>();
    List<Map<String, Object>> ranTests = new ArrayList<>();
    List<Map<String, Object>> failures = new ArrayList<>();
    for (Maven.Build module : tested()) {
      Child.Ran ran =
          Child.run(
              module, runners.get(module), Collections.emptyList(), "run",
              Collections.emptyList(), work.resolve("run"), environment());
      if (ran.problem() != null) {
        return Protocol.error(request.id, "baseline_failed", ran.problem(), true);
      }
      plain.put(module, ran);
      for (Map<?, ?> event : ran.of("test")) {
        String id = named(module, (String) event.get("id"));
        String outcome = (String) event.get("outcome");
        // A test that was skipped, or that gave up on an assumption, looked at nothing.
        if (!outcome.equals("passed") && !outcome.equals("failed")) {
          continue;
        }
        tests.put(id, module);
        Map<String, Object> test = new LinkedHashMap<>();
        test.put("test", id);
        test.put("file", fileOf(module, (String) event.get("id")));
        test.put("duration_ms", event.get("ms"));
        ranTests.add(test);
        if (outcome.equals("failed")) {
          Map<String, Object> failure = new LinkedHashMap<>();
          failure.put("test", id);
          failure.put("message", String.valueOf(event.get("message")));
          failures.add(failure);
        }
      }
    }
    // A red baseline ends the run: there is nothing to select tests for.
    List<Map<String, Object>> reached = failures.isEmpty() ? coverage(plain) : null;
    String spilled = null;
    if (reached != null && Json.encode(reached).getBytes(StandardCharsets.UTF_8).length > hello.inlineLimit) {
      // Past what the kaikai side takes on a line, the same entries go to a file in the reni.
      Path file = Paths.get(hello.reni).resolve("coverage").resolve(hello.worker + ".json");
      Files.createDirectories(file.getParent());
      Files.write(file, (Json.encode(reached) + "\n").getBytes(StandardCharsets.UTF_8));
      spilled = file.toString();
      reached = null;
    }
    return Protocol.baselineDone(
        request.id, ms(started), ranTests, failures, reached, spilled,
        differences(project, environment().keySet()));
  }

  /**
   * How this run of the suite is unlike {@code mvn test} or {@code gradle test} in the project.
   *
   * <p>What a test can tell, and nothing else: a suite that is green under the build and red
   * here is red for one of these.
   *
   * @param passedOn the names of the environment variables the tests' JVM is given
   */
  static List<String> differences(Maven.Reactor project, Collection<String> passedOn) {
    boolean gradle = project.gradle;
    List<String> out = new ArrayList<>();
    out.add(
        "it ran in a copy of the project kept in the reni, "
            + project.root
            + ": `.git` and symbolic links are not copied, and of what a build leaves in "
            + (gradle ? "`build`" : "`target`")
            + " only the classes and resources the tests run with are built again");
    out.add(
        "each module's tests ran in one JVM, "
            + Paths.get(System.getProperty("java.home"), "bin", "java")
            + ", started by the kalku and not by "
            + (gradle ? "Gradle" : "surefire")
            + ": on the class path, the kalku's runner first on it, and never on the module "
            + "path");
    out.add(
        gradle
            ? "of what the build says of its tests only the JVM arguments and system "
                + "properties of the `test` task are read, without `-Xmx` and `-Xms`: not its "
                + "`environment`, nor `forkEvery`, nor a plugin that runs a failed test again"
            : "of what the build says of its tests only surefire's `argLine` and "
                + "`systemPropertyVariables` are read: not `environmentVariables`, nor "
                + "`forkCount` and `reuseForks`, nor `rerunFailingTestsCount`, so a test that "
                + "fails is not run again");
    out.add(
        "the tests are the classes named `Test*`, `*Test`, `*Tests` or `*TestCase`: "
            + (gradle
                ? "the filters and tags of the `test` task"
                : "surefire's `includes`, `excludes` and `groups`")
            + " are not read, so a class the build leaves out runs and one named otherwise "
            + "does not");
    out.add(
        "the tests see none of your environment but "
            + String.join(", ", passedOn)
            + ": any other variable set for them in your shell is not there");
    for (Maven.Build module : project.modules) {
      if (module.engineFetched) {
        out.add(
            "tests written for JUnit 4 or TestNG ran on the JUnit Platform, through an engine "
                + "fetched for them, and not through the runner the build has for them: a "
                + "TestNG suite file is not read");
        break;
      }
    }
    return out;
  }

  // The source a test is written in: `a.B$C#m()` is in `a/B.java` under the module's test
  // sources.
  private String fileOf(Maven.Build module, String test) {
    String type = test.contains("#") ? test.substring(0, test.indexOf('#')) : test;
    String outer = type.contains("$") ? type.substring(0, type.indexOf('$')) : type;
    Path file = module.testSources.resolve(outer.replace('.', '/') + ".java");
    return project.root.relativize(file).toString().replace('\\', '/');
  }

  // ---- coverage ------------------------------------------------------------

  // A place a site is in: the lines of its statement, in a source of one module.
  private static final class Place {
    Maven.Build module;
    String source;
    int from;
    int to;
  }

  // Which tests reach which sites, as the entries `baseline_done` carries; or nothing, with
  // the reason said on stderr, wherever the answer cannot be trusted. Nothing is the safe
  // answer: every wekufe is then cast against the whole suite, slower and still right. A wrong
  // answer would leave a site without the test that catches it, and it would never be cast.
  private List<Map<String, Object>> coverage(Map<Maven.Build, Child.Ran> plain) throws IOException {
    try {
      if (plain.isEmpty()) {
        return withheld("the project has no tests");
      }
      Maven.Build any = plain.keySet().iterator().next();
      Maven.Coverage tools = project.coverage;
      if (tools == null) {
        if (project.gradle) {
          return withheld(
              "the coverage agent could not be fetched"
                  + (project.noCoverage == null ? "" : ": " + project.noCoverage));
        }
        tools = Maven.coverage(any, work.resolve("lib"), environment());
      }
      Path reader = work.resolve("reader");
      String refused = Child.compileReader(tools, reader);
      if (refused != null) {
        return withheld("what reads the counts does not compile: " + refused);
      }

      // The places asked about: the statement each site is in.
      Map<String, Place> places = new LinkedHashMap<>();
      for (Maven.Build module : project.modules) {
        for (Path file : Project.sources(Collections.singletonList(module.sources))) {
          String relative = project.root.relativize(file).toString().replace('\\', '/');
          List<Sites.Site> sites;
          try {
            sites =
                Sites.find(
                    relative, Source.read(file).text, new HashSet<>(Spell.CAST),
                    Collections.emptyList());
          } catch (Sites.ParseError | IOException e) {
            continue;
          }
          for (Sites.Site site : sites) {
            String key = relative + ":" + site.start.line;
            int from = Math.min(site.statementLine, site.start.line);
            int to = Math.max(site.end.line, site.start.line);
            Place place = places.get(key);
            if (place == null) {
              place = new Place();
              place.module = module;
              place.source = module.sources.relativize(file).toString().replace('\\', '/');
              place.from = from;
              place.to = to;
              places.put(key, place);
            } else {
              place.from = Math.min(place.from, from);
              place.to = Math.max(place.to, to);
            }
          }
        }
      }

      // Each module's tests run with the agent counting, in that module and in the ones it
      // uses: a test reaches whatever is on its class path.
      Map<String, Set<String>> reach = new LinkedHashMap<>();
      Set<String> everyTest = new HashSet<>();
      long counted = 0;
      for (Map.Entry<Maven.Build, Child.Ran> ran : plain.entrySet()) {
        Maven.Build module = ran.getKey();
        List<Maven.Build> within = new ArrayList<>();
        within.add(module);
        within.addAll(module.uses);
        List<Path> classes = new ArrayList<>();
        for (Maven.Build each : within) {
          classes.add(each.classes);
        }
        Path dumps = work.resolve("coverage").resolve(String.valueOf(project.modules.indexOf(module)));
        Child.Ran watched =
            Child.cover(
                module, runners.get(module), tools, classes, dumps, work.resolve("run"),
                environment());
        if (watched.problem() != null) {
          return withheld(watched.problem());
        }
        // The agent changes when classes are loaded and what is in them. If a test then ends
        // otherwise than it did without it, what was counted is not what the suite does.
        if (!outcomes(ran.getValue()).equals(outcomes(watched))) {
          return withheld("the tests did not end the same way with the agent counting");
        }
        Path log = work.resolve("run").resolve("tests.log");
        if (Files.isRegularFile(log)) {
          String said = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
          if (said.contains("Error while instrumenting") || said.contains("IllegalClassFormatException")) {
            return withheld(
                "JaCoCo " + Maven.JACOCO + " could not count in this project's classes, which "
                    + "this JDK or the project's language level may be too new for");
          }
        }
        List<String> asked = new ArrayList<>();
        for (Map.Entry<String, Place> place : places.entrySet()) {
          Place p = place.getValue();
          if (within.contains(p.module)) {
            asked.add(
                p.module.classes + "\t" + p.source + "\t" + p.from + "\t" + p.to + "\t"
                    + place.getKey());
          }
        }
        List<String> dumped = new ArrayList<>();
        for (Map<?, ?> each : watched.of("cover")) {
          dumped.add(each.get("dump") + "\t" + each.get("id"));
        }
        Path placesFile = dumps.resolve("places");
        Path testsFile = dumps.resolve("tests");
        Path answer = dumps.resolve("reached");
        Files.write(placesFile, asked, StandardCharsets.UTF_8);
        Files.write(testsFile, dumped, StandardCharsets.UTF_8);
        String wrong =
            Child.read(module, tools, reader, dumps, placesFile, testsFile, answer, environment());
        if (wrong != null) {
          return withheld(wrong);
        }
        List<String> ofModule = new ArrayList<>();
        for (String id : outcomes(ran.getValue()).keySet()) {
          ofModule.add(named(module, id));
        }
        for (String line : Files.readAllLines(answer, StandardCharsets.UTF_8)) {
          String[] part = line.split("\t");
          if (part[0].equals("#counted")) {
            counted += Long.parseLong(part[1]);
            continue;
          }
          Set<String> reaching = reach.computeIfAbsent(part[0], k -> new LinkedHashSet<>());
          if (part.length == 2 && part[1].equals("*")) {
            // Every test that could reach it: here, every test of this module.
            reaching.addAll(ofModule);
          } else {
            for (int i = 1; i < part.length; i++) {
              // Only what the baseline itself ran is a test a cast can be given.
              if (ofModule.contains(named(module, part[i]))) {
                reaching.add(named(module, part[i]));
              }
            }
          }
        }
      }
      // A suite that reaches no site at all is not something a suite does: it is an agent
      // that counted nothing, and every site with code would be reported as one no test
      // reaches.
      if (counted == 0 && !places.isEmpty()) {
        return withheld("nothing was counted in any of the project's classes");
      }

      List<Map<String, Object>> entries = new ArrayList<>();
      for (Map.Entry<String, Set<String>> reached : reach.entrySet()) {
        if (reached.getValue().isEmpty()) {
          continue;
        }
        List<String> reaching = new ArrayList<>(reached.getValue());
        Collections.sort(reaching);
        int colon = reached.getKey().lastIndexOf(':');
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("file", reached.getKey().substring(0, colon));
        entry.put("line", Long.parseLong(reached.getKey().substring(colon + 1)));
        entry.put("tests", reaching);
        entries.add(entry);
      }
      return entries;
    } catch (Maven.Failed e) {
      return withheld("the coverage agent could not be fetched: " + e.getMessage());
    }
  }

  private static List<Map<String, Object>> withheld(String why) {
    System.err.println(
        "kalku: per-test coverage is not reportable for this run: "
            + why
            + ". Every wekufe will be cast against the whole suite instead, so this run is "
            + "slower and every survivor is real.");
    return null;
  }

  // How each test that ran ended, by the name its runner gives it.
  private static Map<String, String> outcomes(Child.Ran ran) {
    Map<String, String> out = new java.util.TreeMap<>();
    for (Map<?, ?> event : ran.of("test")) {
      String outcome = (String) event.get("outcome");
      if (outcome.equals("passed") || outcome.equals("failed")) {
        out.put((String) event.get("id"), outcome);
      }
    }
    return out;
  }

  // ---- cast ----------------------------------------------------------------

  // The module a file of the project belongs to: the one it is deepest inside.
  private Maven.Build moduleOf(Path file) {
    Maven.Build found = null;
    for (Maven.Build module : project.modules) {
      if (file.startsWith(module.dir)
          && (found == null || module.dir.getNameCount() > found.dir.getNameCount())) {
        found = module;
      }
    }
    return found;
  }

  // What a cast compiled: the class directories that go before every module's own, and for
  // the modules whose tests were compiled again, where those are.
  private static final class Compiled {
    final List<Path> classes = new ArrayList<>();
    final Map<Maven.Build, Path> tests = new HashMap<>();
    Compiler.Result result = new Compiler.Result(null);

    List<Path> before(Maven.Build module) {
      List<Path> out = new ArrayList<>();
      if (tests.containsKey(module)) {
        out.add(tests.get(module));
      }
      out.addAll(classes);
      return out;
    }
  }

  private String cast(Protocol.Request request) throws IOException {
    if (project == null) {
      return Protocol.error(request.id, "not_ready", "`prepare` has to come first", false);
    }
    Map<Maven.Build, List<String>> chosen = new LinkedHashMap<>();
    for (Maven.Build module : tested()) {
      chosen.put(module, new ArrayList<>());
    }
    for (String test : request.tests) {
      if (!tests.containsKey(test)) {
        return Protocol.error(
            request.id, "unknown_test", "`" + test + "` is not a test this kalku knows", false);
      }
      chosen.get(tests.get(test)).add(local(test));
    }
    // No test to run is no cast: the runner takes an empty choice for the whole suite, and a
    // test nobody chose would then be what killed.
    if (request.tests.isEmpty()) {
      return unjudged(request, "no test was selected for this wekufe");
    }
    long started = System.nanoTime();
    Path file = project.root.resolve(request.file).normalize();
    Maven.Build module = null;
    Source src;
    try {
      if (!file.startsWith(project.root)) {
        throw new IOException("it is outside the project");
      }
      module = moduleOf(file);
      if (module == null) {
        throw new IOException("it is in no module of the project");
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

    Compiled compiled = compile(module, file, wekufe, request.dependents, work.resolve("cast"));
    if (!compiled.result.ok()) {
      // What does not compile has to be the wekufe, not the way this kalku compiles.
      Compiler.Result control = unchanged(module, file, src.text, request.dependents);
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
          request.id, request.wekufe, "compile_error", null, compiled.result.error, ms(started));
    }

    // The tests of each module run in a JVM of that module's own, one after another, until
    // one of them notices.
    int passed = 0;
    for (Map.Entry<Maven.Build, List<String>> each : chosen.entrySet()) {
      Maven.Build tested = each.getKey();
      if (each.getValue().isEmpty()) {
        continue;
      }
      // The compiler runs in this process and is not stopped half way; what was asked
      // meanwhile is heard before a JVM is started for nothing.
      Protocol.Request stop = aborting(request.id, 0);
      Child.Running running = null;
      if (stop == null && !ended) {
        running =
            Child.start(
                tested, runners.get(tested), compiled.before(tested), "run", each.getValue(),
                work.resolve("run"), environment());
        while (!running.ended(0) && stop == null && !ended) {
          stop = aborting(request.id, 20);
        }
      }
      if (stop != null || ended) {
        boolean gone = running == null || running.ended(0) || running.kill();
        if (stop != null) {
          say(Protocol.aborted(stop.id, stop.cast, gone));
        }
        return null;
      }
      Child.Ran ran = running.finish();
      if (ran.problem() != null) {
        return unjudged(request, ran.problem());
      }
      for (Map<?, ?> event : ran.of("test")) {
        String id = named(tested, (String) event.get("id"));
        if ("failed".equals(event.get("outcome"))) {
          // What failed may be a class and not one of its tests: then no test is named.
          return Protocol.castDone(
              request.id, request.wekufe, "killed", tests.containsKey(id) ? id : null, null,
              ms(started));
        }
        if ("passed".equals(event.get("outcome")) && request.tests.contains(id)) {
          passed++;
        }
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

  // Compiles a cast's classes under `into`.
  //
  // One file is compiled for most wekufe: every other class still fits it, in its own module
  // and in the ones that use it. A wekufe in a constant is in every class the compiler copied
  // the constant into, and that includes the tests and the modules that use this one, so all
  // of those are compiled again, sources and tests.
  private Compiled compile(
      Maven.Build module, Path file, String text, boolean dependents, Path into)
      throws IOException {
    Project.clear(into);
    Compiled out = new Compiled();
    if (!dependents) {
      Path classes = into.resolve("classes");
      List<Path> against = new ArrayList<>();
      against.add(module.classes);
      against.addAll(module.libraries);
      out.classes.add(classes);
      out.result =
          Compiler.compile(
              Collections.singletonList(file), file, text, against, classes,
              module.compilerFlags, project.root);
      return out;
    }
    // This module, then the ones that use it, in the order Maven builds them: each is
    // compiled against what was compiled again before it.
    List<Maven.Build> again = new ArrayList<>();
    for (Maven.Build each : project.modules) {
      if (each == module || each.uses.contains(module)) {
        again.add(each);
      }
    }
    for (Maven.Build each : again) {
      List<Path> sources = Project.sources(Collections.singletonList(each.sources));
      // A module can be nothing but tests of another: it has no code of its own to compile.
      if (sources.isEmpty()) {
        continue;
      }
      Path classes = into.resolve(String.valueOf(project.modules.indexOf(each))).resolve("classes");
      List<Path> against = new ArrayList<>(out.classes);
      against.add(each.classes);
      against.addAll(each.libraries);
      out.result =
          Compiler.compile(
              sources,
              each == module ? file : null,
              each == module ? text : null,
              against, classes, each.compilerFlags, project.root);
      if (!out.result.ok()) {
        return out;
      }
      out.classes.add(0, classes);
    }
    for (Maven.Build each : again) {
      List<Path> sources = Project.sources(Collections.singletonList(each.testSources));
      if (sources.isEmpty()) {
        continue;
      }
      Path classes =
          into.resolve(String.valueOf(project.modules.indexOf(each))).resolve("test-classes");
      List<Path> against = new ArrayList<>(out.classes);
      against.add(each.classes);
      against.add(each.testClasses);
      against.addAll(each.libraries);
      out.result =
          Compiler.compile(
              sources, null, null, against, classes, each.testCompilerFlags, project.root);
      if (!out.result.ok()) {
        return out;
      }
      out.tests.put(each, classes);
    }
    return out;
  }

  private Compiler.Result unchanged(Maven.Build module, Path file, String text, boolean dependents)
      throws IOException {
    String key = dependents ? "*" + module.name : file.toString();
    if (!unchanged.containsKey(key)) {
      unchanged.put(key, compile(module, file, text, dependents, work.resolve("control")).result);
    }
    return unchanged.get(key);
  }
}
