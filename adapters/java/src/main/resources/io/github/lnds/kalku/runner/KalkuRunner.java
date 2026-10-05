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
    for (String line : asked.subList(1, asked.size())) {
      if (line.startsWith("root:")) {
        roots.add(Paths.get(line.substring(5)));
      } else if (line.startsWith("test:")) {
        tests.add(line.substring(5));
      }
    }
    try (Writer out = Files.newBufferedWriter(Paths.get(args[1]), StandardCharsets.UTF_8)) {
      try {
        Launcher launcher = LauncherFactory.create();
        LauncherDiscoveryRequest request = request(roots, tests);
        if (mode.equals("discover")) {
          for (String id : found(launcher.discover(request))) {
            say(out, "{\"e\":\"found\",\"id\":" + quoted(id) + "}");
          }
        } else {
          Recorder recorder = new Recorder();
          launcher.execute(request, recorder);
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

  private static LauncherDiscoveryRequest request(Set<Path> roots, List<String> tests) {
    LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request();
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

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
      plan = testPlan;
    }

    @Override
    public void executionStarted(TestIdentifier id) {
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
