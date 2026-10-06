package io.github.lnds.kalku.runner;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * Runs a project's tests for the Java kalku, in a JVM of its own, and writes down what each
 * one did.
 *
 * <p>This file is not compiled with the kalku. It travels inside its jar as source and is
 * compiled in the reni, by the project's own JDK, against the JUnit Platform the project
 * itself uses: there is no version of the platform the kalku was built for and the project
 * has to live with.
 *
 * <p>It is asked through a file and answers into another, one JSON object a line. Standard
 * output is the tests' to write on. A test is a method: the invocations of a parameterized
 * test and the tests a factory makes are counted as the method that gives them.
 */
public final class KalkuRunner {
  private KalkuRunner() {}

  // The classes Maven's own runner takes for tests when a project says nothing else.
  private static final String TEST_CLASSES = "^(.*\\.)?(Test[^.]*|[^.]*Tests?|[^.]*TestCase)$";

  public static void main(String[] args) throws IOException {
    leaveWithTheKalku();
    List<String> asked = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
    String mode = asked.get(0);
    Set<Path> roots = new LinkedHashSet<>();
    List<String> tests = new ArrayList<>();
    Path dumps = null;
    Path classes = null;
    for (String line : asked.subList(1, asked.size())) {
      if (line.startsWith("root:")) {
        roots.add(Paths.get(line.substring(5)));
      } else if (line.startsWith("test:")) {
        tests.add(line.substring(5));
      } else if (line.startsWith("dumps:")) {
        dumps = Paths.get(line.substring(6));
      } else if (line.startsWith("classes:")) {
        classes = Paths.get(line.substring(8));
      }
    }
    try (Writer out = Files.newBufferedWriter(Paths.get(args[1]), StandardCharsets.UTF_8)) {
      try {
        Launcher launcher = LauncherFactory.create();
        boolean covering = mode.equals("cover");
        LauncherDiscoveryRequest request = request(roots, tests, covering);
        if (mode.equals("discover")) {
          for (String id : found(launcher.discover(request))) {
            say(out, "{\"e\":\"found\",\"id\":" + quoted(id) + "}");
          }
        } else {
          Recorder recorder = new Recorder();
          if (covering) {
            recorder.cover = new Cover(dumps);
            recorder.cover.initialise(classes);
          }
          launcher.execute(request, recorder);
          if (covering) {
            recorder.cover.outside();
            for (String[] taken : recorder.cover.taken) {
              say(
                  out,
                  "{\"e\":\"cover\",\"id\":" + quoted(taken[0]) + ",\"dump\":" + quoted(taken[1]) + "}");
            }
          }
          for (Map.Entry<String, Result> entry : recorder.results.entrySet()) {
            Result r = entry.getValue();
            say(
                out,
                "{\"e\":\"test\",\"id\":"
                    + quoted(entry.getKey())
                    + ",\"outcome\":"
                    + quoted(r.outcome)
                    + ",\"ms\":"
                    + (r.nanos / 1_000_000)
                    + (r.message == null ? "" : ",\"message\":" + quoted(r.message))
                    + "}");
          }
        }
        say(out, "{\"e\":\"done\"}");
      } catch (Throwable t) {
        say(out, "{\"e\":\"crashed\",\"message\":" + quoted(String.valueOf(t)) + "}");
      }
    }
    // A test may leave a thread running that would keep this JVM alive.
    System.exit(0);
  }

  // Whatever the kalku started ends with it: its end closes this process's input.
  private static void leaveWithTheKalku() {
    Thread watch =
        new Thread(
            () -> {
              try {
                while (System.in.read() >= 0) {
                  // Nothing is ever sent; only the end is waited for.
                }
              } catch (IOException e) {
                // A broken pipe is an end too.
              }
              Runtime.getRuntime().halt(70);
            },
            "kalku-watch");
    watch.setDaemon(true);
    watch.start();
  }

  private static LauncherDiscoveryRequest request(
      Set<Path> roots, List<String> tests, boolean covering) {
    LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request();
    if (covering) {
      // What a test reached is only known while one test runs at a time.
      builder.configurationParameter("junit.jupiter.execution.parallel.enabled", "false");
    }
    if (tests.isEmpty()) {
      builder.selectors(DiscoverySelectors.selectClasspathRoots(roots));
      builder.filters(ClassNameFilter.includeClassNamePatterns(TEST_CLASSES));
    } else {
      List<DiscoverySelector> selectors = new ArrayList<>();
      for (String id : tests) {
        selectors.add(
            id.contains("#")
                ? DiscoverySelectors.selectMethod(id)
                : DiscoverySelectors.selectClass(id));
      }
      builder.selectors(selectors);
    }
    return builder.build();
  }

  private static Set<String> found(TestPlan plan) {
    Set<String> ids = new LinkedHashSet<>();
    for (TestIdentifier root : plan.getRoots()) {
      for (TestIdentifier each : plan.getDescendants(root)) {
        if (each.isTest() || source(each) instanceof MethodSource) {
          String id = name(plan, each);
          if (id != null) {
            ids.add(id);
          }
        }
      }
    }
    return ids;
  }

  private static TestSource source(TestIdentifier id) {
    Optional<TestSource> source = id.getSource();
    return source.isPresent() ? source.get() : null;
  }

  // The method a test belongs to, `a.B#m(int, String)`; or its class, when what failed is not
  // a method at all but what a class does before its tests.
  private static String name(TestPlan plan, TestIdentifier id) {
    String owner = null;
    for (TestIdentifier at = id; at != null; at = plan.getParent(at).orElse(null)) {
      TestSource source = source(at);
      if (source instanceof MethodSource) {
        MethodSource method = (MethodSource) source;
        String takes = method.getMethodParameterTypes();
        return method.getClassName()
            + "#"
            + method.getMethodName()
            + "("
            + (takes == null ? "" : takes)
            + ")";
      }
      if (source instanceof ClassSource && owner == null) {
        owner = ((ClassSource) source).getClassName();
      }
    }
    return owner;
  }

  private static final class Result {
    String outcome = "aborted";
    String message;
    long nanos;
  }

  private static final class Recorder implements TestExecutionListener {
    final Map<String, Result> results = new LinkedHashMap<>();
    private final Map<String, Long> started = new ConcurrentHashMap<>();
    private TestPlan plan;
    Cover cover;

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
      plan = testPlan;
    }

    @Override
    public void executionStarted(TestIdentifier id) {
      if (cover != null && id.isTest()) {
        cover.outside();
      }
      started.put(id.getUniqueId(), System.nanoTime());
    }

    @Override
    public synchronized void executionFinished(TestIdentifier id, TestExecutionResult result) {
      boolean failed = result.getStatus() == TestExecutionResult.Status.FAILED;
      // A container that fails is a class whose tests never ran: that is a failure of theirs.
      if (!id.isTest() && !failed) {
        return;
      }
      String name = name(plan, id);
      if (cover != null && id.isTest()) {
        cover.test(name);
      }
      if (name == null) {
        return;
      }
      Result r = results.computeIfAbsent(name, n -> new Result());
      Long at = started.get(id.getUniqueId());
      if (at != null && id.isTest()) {
        r.nanos += System.nanoTime() - at;
      }
      if (failed) {
        if (!r.outcome.equals("failed")) {
          r.outcome = "failed";
          r.message = said(result.getThrowable().orElse(null));
        }
      } else if (result.getStatus() == TestExecutionResult.Status.SUCCESSFUL
          && !r.outcome.equals("failed")) {
        r.outcome = "passed";
      }
    }

    @Override
    public synchronized void executionSkipped(TestIdentifier id, String reason) {
      String name = name(plan, id);
      if (name != null && id.isTest()) {
        results.computeIfAbsent(name, n -> new Result()).outcome = "skipped";
      }
    }
  }

  /**
   * What each test reached, taken from the agent that counts it.
   *
   * <p>The agent is JaCoCo's, started with this JVM. It is asked through reflection, so this
   * file compiles where there is no agent. Nothing it counted is thrown away: what ran while
   * a test did is the test's, and everything else — what a class does when it is first used,
   * what runs before and between tests — is kept apart, because every test may depend on it.
   */
  private static final class Cover {
    final List<String[]> taken = new ArrayList<>();
    private final Path dir;
    private final Object agent;
    private final java.lang.reflect.Method take;

    Cover(Path dir) throws Exception {
      this.dir = dir;
      Files.createDirectories(dir);
      agent = Class.forName("org.jacoco.agent.rt.RT").getMethod("getAgent").invoke(null);
      take =
          Class.forName("org.jacoco.agent.rt.IAgent")
              .getMethod("getExecutionData", boolean.class);
    }

    // What was counted since the last time, which starts the count again.
    private byte[] counted() {
      try {
        return (byte[]) take.invoke(agent, true);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }

    // A class runs its static initialiser once in the life of a JVM, during whichever test
    // happens to use it first, and that test would be the only one credited with it. So every
    // class of the project is initialised here, before any test, and what that ran is kept as
    // what every test may depend on.
    void initialise(Path classes) throws IOException {
      List<String> names = new ArrayList<>();
      try (java.util.stream.Stream<Path> all = Files.walk(classes)) {
        all.filter(p -> p.toString().endsWith(".class"))
            .sorted()
            .forEach(
                p -> {
                  String name = classes.relativize(p).toString().replace('\\', '/');
                  name = name.substring(0, name.length() - 6).replace('/', '.');
                  if (!name.endsWith("module-info") && !name.endsWith("package-info")) {
                    names.add(name);
                  }
                });
      }
      for (String name : names) {
        try {
          Class.forName(name, true, KalkuRunner.class.getClassLoader());
        } catch (Throwable t) {
          // A class that cannot be initialised here will say so in the test that needs it.
        }
      }
      Files.write(dir.resolve("init.exec"), counted());
    }

    synchronized void outside() {
      try {
        Files.write(
            dir.resolve("outside.exec"),
            counted(),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }

    synchronized void test(String name) {
      String file = "t" + taken.size() + ".exec";
      try {
        Files.write(dir.resolve(file), counted());
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      taken.add(new String[] {name == null ? "" : name, file});
    }
  }

  private static String said(Throwable t) {
    String text = t == null ? "the test failed" : String.valueOf(t);
    return text.length() > 2000 ? text.substring(0, 2000) : text;
  }

  private static void say(Writer out, String line) throws IOException {
    out.write(line);
    out.write('\n');
    out.flush();
  }

  private static String quoted(String text) {
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"' || c == '\\') {
        out.append('\\').append(c);
      } else if (c == '\n') {
        out.append("\\n");
      } else if (c == '\r') {
        out.append("\\r");
      } else if (c == '\t') {
        out.append("\\t");
      } else if (c < 0x20) {
        out.append(String.format("\\u%04X", (int) c));
      } else {
        out.append(c);
      }
    }
    return out.append('"').toString();
  }
}
