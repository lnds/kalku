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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What Gradle knows about a project, asked of Gradle.
 *
 * <p>A Gradle build is a program, and what it does is only known by running it. So the copy
 * in the reni is built by Gradle itself, with a script added from outside the project that
 * makes each of its projects write down what a cast needs: where its classes and sources are,
 * what its tests run with, what the build tells the compiler and the tests' JVM. The same run
 * has Gradle fetch what the kalku needs and the project does not bring, from the repositories
 * the project itself names.
 *
 * <p>Gradle is started without its daemon. A daemon outlives the build that started it, and
 * what a kalku starts ends with it.
 */
final class Gradle {
  private Gradle() {}

  private static final List<String> MARKERS =
      Arrays.asList("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

  /** True when a directory is, or is the top of, a Gradle build. */
  static boolean builds(Path dir) {
    for (String marker : MARKERS) {
      if (Files.isRegularFile(dir.resolve(marker))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Builds the copy, tests included but not run, and reads back what Gradle wrote down.
   *
   * @param fresh false when nothing changed since a build that is still there
   */
  static Maven.Reactor build(Path project, Path work, Map<String, String> env, boolean fresh)
      throws Maven.Failed, IOException {
    Path described = work.resolve("gradle");
    Path builtBy = described.resolve("kalku.jdk");
    String jdk = System.getProperty("java.version", "") + " " + System.getProperty("java.home", "");
    boolean sameJdk =
        Files.isRegularFile(builtBy)
            && jdk.equals(new String(Files.readAllBytes(builtBy), StandardCharsets.UTF_8));
    if (fresh || !sameJdk) {
      Project.clear(described);
      Path init = work.resolve("lib").resolve("kalku.init.gradle");
      Files.createDirectories(init.getParent());
      Files.write(init, shipped("gradle/kalku.init.gradle"));
      run(
          project,
          work.resolve("gradle.log"),
          env,
          "-I",
          init.toString(),
          "-Pkalku.out=" + described,
          "-Pkalku.jacoco=" + Maven.JACOCO,
          "-Pkalku.bom=" + Maven.JUNIT_BOM,
          "-Pkalku.vintage=" + Maven.VINTAGE,
          "-Pkalku.testng=" + Maven.TESTNG_ENGINE,
          "kalkuDescribe");
      Files.write(builtBy, jdk.getBytes(StandardCharsets.UTF_8));
    }

    Maven.Reactor reactor = new Maven.Reactor();
    reactor.root = project;
    reactor.gradle = true;
    Map<Maven.Build, Path> jars = new LinkedHashMap<>();
    List<Path> files;
    try (Stream<Path> all = Files.list(described)) {
      files =
          all.filter(p -> p.toString().endsWith(".describe")).sorted().collect(Collectors.toList());
    }
    for (Path file : files) {
      read(reactor, file, jars);
    }
    if (reactor.modules.isEmpty()) {
      throw new Maven.Failed("no project of this Gradle build has Java code to measure");
    }
    for (Maven.Build build : reactor.modules) {
      for (Maven.Build other : reactor.modules) {
        if (other != build
            && (build.libraries.contains(other.classes)
                || build.libraries.contains(jars.get(other)))) {
          build.uses.add(other);
        }
      }
    }
    // A module is compiled again after the ones it uses, so they come first.
    reactor.modules.sort((a, b) -> a.uses.contains(b) ? 1 : b.uses.contains(a) ? -1 : 0);
    return reactor;
  }

  // ---- running Gradle --------------------------------------------------------

  private static void run(Path project, Path log, Map<String, String> env, String... arguments)
      throws Maven.Failed, IOException {
    List<String> command = new ArrayList<>();
    command.add(executable(project, env));
    // No daemon, nothing drawn, and nothing asked of whoever is not there.
    command.addAll(Arrays.asList("--no-daemon", "-q", "--console=plain"));
    command.addAll(Arrays.asList(arguments));
    ProcessBuilder builder = new ProcessBuilder(command).directory(project.toFile());
    builder.environment().clear();
    builder.environment().putAll(env);
    builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
    builder.redirectErrorStream(true).redirectOutput(log.toFile());
    int exit;
    try {
      exit = Maven.heard(builder.start(), log);
    } catch (IOException e) {
      throw new Maven.Failed(
          "cannot run Gradle (`"
              + command.get(0)
              + "`): "
              + e.getMessage()
              + ". Add the Gradle wrapper to the project, put `gradle` on the PATH, or set "
              + "KALKU_GRADLE.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Maven.Failed("interrupted while Gradle was running");
    }
    if (exit != 0) {
      throw new Maven.Failed(said(log, exit));
    }
  }

  // The project's own wrapper pins the Gradle its build was written for.
  private static String executable(Path project, Map<String, String> env) {
    String chosen = env.get("KALKU_GRADLE");
    if (chosen != null && !chosen.isEmpty()) {
      return chosen;
    }
    Path wrapper = project.resolve("gradlew");
    return Files.isExecutable(wrapper) ? wrapper.toString() : "gradle";
  }

  // What Gradle says went wrong, which it sets apart from its advice about it.
  private static String said(Path log, int exit) throws IOException {
    List<String> wrong = new ArrayList<>();
    boolean in = false;
    for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
      if (line.startsWith("* What went wrong:")) {
        in = true;
      } else if (line.startsWith("* Try:") || line.startsWith("* Exception is:")) {
        in = false;
      } else if (in && !line.trim().isEmpty()) {
        wrong.add(line.trim());
      } else if (line.contains("error:")) {
        // The compiler's own words come before Gradle's account of them.
        wrong.add(line.trim());
      }
    }
    if (wrong.isEmpty()) {
      return "Gradle exited with " + exit + " and did not say what went wrong; see " + log;
    }
    return String.join("\n", wrong.subList(0, Math.min(wrong.size(), 20)));
  }

  private static byte[] shipped(String name) throws IOException {
    try (InputStream in = Gradle.class.getResourceAsStream(name)) {
      if (in == null) {
        throw new IOException(name + " is missing from the kalku");
      }
      ByteArrayOutputStream held = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) >= 0) {
        held.write(buffer, 0, n);
      }
      return held.toByteArray();
    }
  }

  // ---- reading what it wrote -------------------------------------------------

  private static void read(Maven.Reactor reactor, Path file, Map<Maven.Build, Path> jars)
      throws Maven.Failed, IOException {
    Map<String, List<String>> said = new LinkedHashMap<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      int at = line.indexOf('=');
      if (at > 0) {
        said.computeIfAbsent(line.substring(0, at), k -> new ArrayList<>())
            .add(line.substring(at + 1));
      }
    }
    Maven.Build build = new Maven.Build();
    build.project = reactor.root;
    build.dir = Paths.get(one(said, "dir")).toRealPath();
    build.name = reactor.root.relativize(build.dir).toString().replace('\\', '/');
    String called = build.name.isEmpty() ? "the project" : "the project `" + build.name + "`";
    build.classes = Paths.get(one(said, "classes"));
    build.testClasses = Paths.get(one(said, "testClasses"));
    // A project can keep its sources in more than one place; the first is what is measured.
    build.sources = first(said, "sources", build.dir.resolve("src/main/java"));
    build.testSources = first(said, "testSources", build.dir.resolve("src/test/java"));
    for (String entry : said.getOrDefault("classpath", new ArrayList<>())) {
      Path path = Paths.get(entry);
      // Its own classes go first on every class path a cast makes; the rest is kept as it is,
      // what the build copies beside the classes included.
      if (!path.equals(build.classes) && !path.equals(build.testClasses)) {
        build.libraries.add(path);
      }
    }
    build.compilerFlags.addAll(flags(said.getOrDefault("flag", new ArrayList<>())));
    build.testCompilerFlags.addAll(flags(said.getOrDefault("testFlag", new ArrayList<>())));
    build.jvmFlags.addAll(said.getOrDefault("jvm", new ArrayList<>()));
    for (String variable : said.getOrDefault("env", new ArrayList<>())) {
      int is = variable.indexOf('=');
      build.environment.put(variable.substring(0, is), variable.substring(is + 1));
    }
    build.suite =
        Suite.gradle(
            said.getOrDefault("include", new ArrayList<>()),
            said.getOrDefault("exclude", new ArrayList<>()));
    build.platform = said.containsKey("platform") ? one(said, "platform") : null;
    for (String jar : said.getOrDefault("launcher", new ArrayList<>())) {
      if (Paths.get(jar).getFileName().toString().startsWith("junit-platform-launcher-")) {
        build.launcher = Paths.get(jar);
      }
    }
    if (said.containsKey("jar")) {
      jars.put(build, Paths.get(one(said, "jar")));
    }
    // Tests written for JUnit 4 or for TestNG: Gradle fetched the engines that run them on the
    // JUnit Platform, and the launcher of the same version.
    if (build.platform == null && said.containsKey("engine")) {
      Maven.enginesFor(build.libraries, called);
      List<Path> fetched = new ArrayList<>();
      for (String jar : said.get("engine")) {
        fetched.add(Paths.get(jar));
      }
      build.launcher = Maven.withEngine(build.libraries, fetched);
      build.platform = Maven.PLATFORM;
      build.engineFetched = true;
    }

    // The tests have to run on the JDK this kalku runs on, which is the one it compiles with.
    if (said.containsKey("javaHome")) {
      Path theirs = Paths.get(one(said, "javaHome")).toRealPath();
      Path ours = Paths.get(System.getProperty("java.home")).toRealPath();
      if (!theirs.equals(ours)) {
        throw new Maven.Failed(
            called
                + " runs its tests on another JDK than the one kalku was started with ("
                + theirs
                + ", a toolchain of the build, against "
                + ours
                + "); set JAVA_HOME or KALKU_JAVA to that JDK");
      }
    }
    boolean hasTests = !Project.sources(java.util.Collections.singletonList(build.testSources)).isEmpty();
    if (hasTests && (build.platform == null || build.launcher == null)) {
      throw new Maven.Failed(
          "none of JUnit 5, JUnit 4 and TestNG is among the test dependencies of "
              + called
              + ", and kalku does not know another way to run its tests");
    }
    if (!hasTests) {
      build.platform = null;
    }

    if (reactor.coverage == null && said.containsKey("agent") && said.containsKey("reader")) {
      Maven.Coverage coverage = new Maven.Coverage();
      coverage.agent = Paths.get(one(said, "agent"));
      for (String jar : said.get("reader")) {
        coverage.readers.add(Paths.get(jar));
      }
      reactor.coverage = coverage;
    } else if (said.containsKey("noCoverage")) {
      reactor.noCoverage = one(said, "noCoverage");
    }
    reactor.modules.add(build);
  }

  private static String one(Map<String, List<String>> said, String key) throws Maven.Failed {
    List<String> values = said.get(key);
    if (values == null || values.isEmpty()) {
      throw new Maven.Failed("Gradle did not say `" + key + "` for one of its projects");
    }
    return values.get(0);
  }

  private static Path first(Map<String, List<String>> said, String key, Path otherwise) {
    List<String> values = said.get(key);
    return values == null || values.isEmpty() ? otherwise : Paths.get(values.get(0));
  }

  // What a build asks of its warnings is not asked of a wekufe, as with Maven.
  private static List<String> flags(List<String> stated) {
    List<String> out = new ArrayList<>(stated);
    out.removeIf(a -> a.isEmpty() || a.equals("-Werror") || a.startsWith("-Xlint"));
    return out;
  }
}
