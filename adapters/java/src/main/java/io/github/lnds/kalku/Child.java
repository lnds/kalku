package io.github.lnds.kalku;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The JVM the project's tests run in, one for each run of them.
 *
 * <p>The tests never run in the kalku's own JVM. A wekufe that calls {@code System.exit}, that
 * loops, or that takes all the memory would take the kalku with it, and what one run left in
 * a static field would be there for the next. A JVM that is thrown away has nothing to reset:
 * every run starts from the classes on disk and ends with its process.
 */
final class Child {
  private Child() {}

  // What `-XX:+ExitOnOutOfMemoryError` exits with.
  private static final int OUT_OF_MEMORY = 3;

  private static final String RUNNER = "io.github.lnds.kalku.runner.KalkuRunner";
  private static final String RUNNER_SOURCE = "runner/KalkuRunner.java";

  /** What a run of the tests said, and how its JVM ended. */
  static final class Ran {
    final List<Map<?, ?>> events = new ArrayList<>();
    int exit;
    // True when the runner got to the end of what it was asked.
    boolean done;
    String crashed;

    /** Why there is no verdict in this, or null when there is one. */
    String problem() {
      if (crashed != null) {
        return "the tests could not be run: " + crashed;
      }
      if (done) {
        return null;
      }
      if (exit == OUT_OF_MEMORY) {
        return "the JVM running the tests ran out of memory";
      }
      return "the JVM running the tests exited with " + exit + " before the tests were over";
    }

    List<Map<?, ?>> of(String kind) {
      return events.stream().filter(e -> kind.equals(e.get("e"))).collect(Collectors.toList());
    }
  }

  /**
   * Compiles the runner against the project's own JUnit Platform, with the project's own JDK.
   *
   * @return what the compiler objected to, or null
   */
  static String compileRunner(Maven.Build build, Path out) throws IOException {
    String source;
    try (InputStream in = Child.class.getResourceAsStream(RUNNER_SOURCE)) {
      if (in == null) {
        return "the runner's source is missing from the kalku";
      }
      ByteArrayOutputStream held = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) >= 0) {
        held.write(buffer, 0, n);
      }
      source = new String(held.toByteArray(), StandardCharsets.UTF_8);
    }
    Project.clear(out);
    Path named = out.resolve("KalkuRunner.java");
    List<Path> classpath = new ArrayList<>(build.libraries);
    classpath.add(build.launcher);
    return Compiler.compile(
            Collections.singletonList(named),
            named,
            source,
            classpath,
            out,
            Collections.emptyList(),
            out)
        .error;
  }

  /** A JVM that is running tests, between its start and what it is found to have said. */
  static final class Running {
    private final Process process;
    private final Path events;

    Running(Process process, Path events) {
      this.process = process;
      this.events = events;
    }

    /** Waits this long for the JVM to end, and says whether it has. */
    boolean ended(long millis) {
      try {
        return process.waitFor(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    /** What the JVM said, once it has ended. */
    Ran finish() throws IOException {
      Ran ran = new Ran();
      try {
        ran.exit = process.waitFor();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
        ran.crashed = "interrupted";
        return ran;
      } finally {
        // Its input stayed open and silent until now: the runner leaves when it closes, which
        // is when this process ends, however it ends.
        process.getOutputStream().close();
      }
      read(ran, events);
      return ran;
    }

    /**
     * Ends the JVM and everything it started, and says whether all of it is gone.
     *
     * <p>What it started is listed before anything is ended: a process whose parent has died
     * belongs to nobody, and can no longer be found through it.
     */
    boolean kill() throws IOException {
      List<ProcessHandle> all = process.descendants().collect(Collectors.toList());
      all.add(process.toHandle());
      for (ProcessHandle each : all) {
        each.destroyForcibly();
      }
      process.getOutputStream().close();
      long deadline = System.nanoTime() + 5_000_000_000L;
      while (System.nanoTime() < deadline) {
        if (all.stream().noneMatch(ProcessHandle::isAlive)) {
          return true;
        }
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      return all.stream().noneMatch(ProcessHandle::isAlive);
    }
  }

  /** Runs the tests in a JVM of their own and waits for it. */
  static Ran run(
      Maven.Build build,
      Path runner,
      List<Path> before,
      String mode,
      List<String> tests,
      Path scratch,
      Map<String, String> env)
      throws IOException {
    return start(build, runner, before, mode, tests, scratch, env).finish();
  }

  /**
   * Starts the tests in a JVM of their own.
   *
   * @param mode {@code discover} to list the tests, {@code run} to run them
   * @param tests the tests to run; none means every test of the project
   * @param before class directories that come before the project's own, as a cast's do
   */
  static Running start(
      Maven.Build build,
      Path runner,
      List<Path> before,
      String mode,
      List<String> tests,
      Path scratch,
      Map<String, String> env)
      throws IOException {
    Files.createDirectories(scratch);
    Path request = scratch.resolve("request");
    Path events = scratch.resolve("events");
    Path arguments = scratch.resolve("java.args");
    Path log = scratch.resolve("tests.log");
    Files.deleteIfExists(events);

    List<String> asked = new ArrayList<>();
    asked.add(mode);
    asked.add("root:" + build.testClasses);
    for (String test : tests) {
      asked.add("test:" + test);
    }
    Files.write(request, asked, StandardCharsets.UTF_8);

    List<Path> classpath = new ArrayList<>();
    classpath.add(runner);
    classpath.addAll(before);
    classpath.add(build.testClasses);
    classpath.add(build.classes);
    classpath.addAll(build.libraries);
    if (!build.libraries.contains(build.launcher)) {
      classpath.add(build.launcher);
    }
    // A class path can be longer than a command line may be, so it is read from a file.
    List<String> options = new ArrayList<>();
    // Assertions on, as the build's own runner has them; and a JVM out of memory ends, so that
    // it is never a test that failed.
    options.add("-ea");
    options.add("-XX:+ExitOnOutOfMemoryError");
    options.addAll(build.jvmFlags);
    options.add("-cp");
    options.add(classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
    Files.write(
        arguments,
        options.stream().map(Child::argument).collect(Collectors.toList()),
        StandardCharsets.UTF_8);

    ProcessBuilder builder =
        new ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + arguments,
                RUNNER,
                request.toString(),
                events.toString())
            .directory(build.project.toFile());
    builder.environment().clear();
    builder.environment().putAll(env);
    // What the tests print is theirs, and is kept beside the run for whoever needs it.
    builder.redirectErrorStream(true).redirectOutput(log.toFile());

    return new Running(builder.start(), events);
  }

  private static void read(Ran ran, Path events) throws IOException {
    if (!Files.isRegularFile(events)) {
      return;
    }
    for (String line : Files.readAllLines(events, StandardCharsets.UTF_8)) {
      try {
        Map<?, ?> event = (Map<?, ?>) Json.decode(line);
        ran.events.add(event);
        if ("done".equals(event.get("e"))) {
          ran.done = true;
        } else if ("crashed".equals(event.get("e"))) {
          ran.crashed = String.valueOf(event.get("message"));
        }
      } catch (Json.Invalid | ClassCastException e) {
        // A line cut short by a JVM that died is the end of what it said.
        return;
      }
    }
  }

  // One argument as the launcher reads it from a file: quoted, with `\` and `"` escaped.
  private static String argument(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
