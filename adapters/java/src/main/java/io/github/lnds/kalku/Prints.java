package io.github.lnds.kalku;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What the tests of one class run, as a fingerprint.
 *
 * <p>A test class runs the classes the coverage agent saw it run, and what every test of its
 * module may depend on: what ran outside any one test or as a class was first used, and the
 * classes with no code, an interface or an annotation, which run without being counted.
 * Classes go in by their bytecode, so a constant the compiler copied into a class changes that
 * class.
 *
 * <p>Around them go what the module's tests read that is not one of those classes: the jars on
 * their class path, the build files, the names of every class the module could find by looking,
 * the files of the module and of the modules it uses that are not compiled sources, the
 * environment the tests are given, the JDK, and this kalku.
 *
 * <p>Which classes went into a fingerprint is kept in the reni under the fingerprint itself, so
 * a later baseline takes the same classes as they are then. A test that would run other classes
 * can only do so because one of these changed.
 *
 * <p>What a test reads from outside its module's directory, and what another test leaves in a
 * static field, are not in it.
 */
final class Prints {
  private static final List<String> COMPILED = Arrays.asList(".java", ".kt", ".groovy", ".scala");
  private static final List<String> BUILD_FILES =
      Arrays.asList(
          "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
          "gradle.properties", "libs.versions.toml", "gradle-wrapper.properties",
          "maven-wrapper.properties", "extensions.xml", "maven.config", "jvm.config");

  /** The tests of one class, and the class files their fingerprint was taken over. */
  static final class Recipe {
    final String module;
    final List<String> tests;
    final List<String> classes;

    Recipe(String module, List<String> tests, Collection<String> classes) {
      this.module = module;
      this.tests = new ArrayList<>(tests);
      this.classes = new ArrayList<>(new TreeSet<>(classes));
    }
  }

  private final Maven.Reactor project;
  private final Path kept;
  private final String setting;
  private final Map<Maven.Build, String> around = new HashMap<>();
  // Jars outside the project, by path, size and time: hashed once for as long as this lives.
  private final Map<String, String> jars;

  /**
   * @param kept where recipes are kept, in the reni
   * @param setting what the tests are run with that no file says: the kalku, the JDK, the
   *     environment
   * @param jars digests of jars from an earlier instance, kept for as long as the kalku lives
   */
  Prints(Maven.Reactor project, Path kept, String setting, Map<String, String> jars) {
    this.project = project;
    this.kept = kept;
    this.setting = setting;
    this.jars = jars;
  }

  /**
   * The fingerprint of the tests of {@code owner}, a class of {@code module}, that ran these
   * class files. Two classes whose tests ran the same classes have two fingerprints.
   */
  String of(Maven.Build module, String owner, Collection<String> classes) throws IOException {
    MessageDigest h = sha256();
    line(h, around(module));
    line(h, "tests " + module.name + " " + owner);
    for (String file : new TreeSet<>(classes)) {
      Path at = project.root.resolve(file);
      line(h, file + " " + (Files.isRegularFile(at) ? digest(at) : "-"));
    }
    return hex(h.digest()).substring(0, 32);
  }

  /** Keeps which tests and classes a fingerprint was taken over. */
  void keep(String print, Recipe recipe) throws IOException {
    Files.createDirectories(kept);
    List<String> lines = new ArrayList<>();
    lines.add(recipe.module);
    lines.add(String.join("\t", recipe.tests));
    lines.addAll(recipe.classes);
    Path temporary = Files.createTempFile(kept, print, ".part");
    Files.write(temporary, lines, StandardCharsets.UTF_8);
    Files.move(temporary, kept.resolve(print), StandardCopyOption.REPLACE_EXISTING);
  }

  /** What a fingerprint was taken over, or null when that was never kept here. */
  Recipe recall(String print) {
    if (!print.matches("[0-9a-f]{32}")) {
      return null;
    }
    try {
      List<String> lines = Files.readAllLines(kept.resolve(print), StandardCharsets.UTF_8);
      if (lines.size() < 2 || lines.get(1).isEmpty()) {
        return null;
      }
      return new Recipe(
          lines.get(0),
          Arrays.asList(lines.get(1).split("\t")),
          lines.subList(2, lines.size()));
    } catch (IOException e) {
      return null;
    }
  }

  // Everything but the classes the tests ran: the same for every test class of a module.
  private String around(Maven.Build module) throws IOException {
    String known = this.around.get(module);
    if (known != null) {
      return known;
    }
    MessageDigest h = sha256();
    line(h, setting);
    line(h, String.join(" ", module.jvmFlags));
    line(h, new java.util.TreeMap<>(module.environment).toString());
    line(h, "reruns " + module.reruns);
    for (Path file : buildFiles()) {
      line(h, "build " + relative(file) + " " + digest(file));
    }
    List<Path> classpath = new ArrayList<>(module.libraries);
    if (module.launcher != null && !classpath.contains(module.launcher)) {
      classpath.add(module.launcher);
    }
    for (Path library : classpath) {
      if (tracked(module, library)) {
        continue;
      }
      line(h, "library " + library.getFileName() + " " + library(library));
    }
    List<Path> dirs = new ArrayList<>(Arrays.asList(module.classes, module.testClasses));
    for (Maven.Build used : module.uses) {
      dirs.add(used.classes);
    }
    for (Path dir : dirs) {
      for (Path file : files(dir)) {
        String name = dir.relativize(file).toString().replace('\\', '/');
        // A class goes in by its name: its bytes go in only where a test ran it.
        line(h, name.endsWith(".class") ? "class " + name : "resource " + name + " " + digest(file));
      }
    }
    List<Maven.Build> read = new ArrayList<>();
    read.add(module);
    read.addAll(module.uses);
    for (Maven.Build each : read) {
      for (Path file : data(each)) {
        line(h, "data " + relative(file) + " " + digest(file));
      }
    }
    String done = hex(h.digest());
    this.around.put(module, done);
    return done;
  }

  // A used module's classes are counted by the agent, class by class.
  private static boolean tracked(Maven.Build module, Path library) {
    for (Maven.Build used : module.uses) {
      if (library.equals(used.classes)) {
        return true;
      }
    }
    return false;
  }

  private String library(Path library) throws IOException {
    if (Files.isDirectory(library)) {
      MessageDigest h = sha256();
      for (Path file : files(library)) {
        line(h, library.relativize(file) + " " + digest(file));
      }
      return hex(h.digest());
    }
    if (!Files.isRegularFile(library)) {
      return "-";
    }
    String key =
        library + "\t" + Files.size(library) + "\t" + Files.getLastModifiedTime(library).toMillis();
    String known = jars.get(key);
    if (known == null) {
      known = digest(library);
      jars.put(key, known);
    }
    return known;
  }

  // The files of the project that say how it is built, wherever they are in it.
  private List<Path> buildFiles() throws IOException {
    List<Path> out = new ArrayList<>();
    try (Stream<Path> all = Files.walk(project.root)) {
      for (Path p : (Iterable<Path>) all::iterator) {
        if (Files.isRegularFile(p)
            && !inOutput(p)
            && (BUILD_FILES.contains(p.getFileName().toString())
                || relative(p).startsWith(".mvn/"))) {
          out.add(p);
        }
      }
    }
    out.sort(null);
    return out;
  }

  // What a module's tests may read by its path: the module's files that are neither compiled
  // sources nor what a build wrote, nor in another module.
  private List<Path> data(Maven.Build module) throws IOException {
    List<Path> roots = new ArrayList<>(module.sources);
    roots.addAll(module.testSources);
    List<Path> out = new ArrayList<>();
    try (Stream<Path> all = Files.walk(module.dir)) {
      for (Path p : (Iterable<Path>) all::iterator) {
        if (!Files.isRegularFile(p) || inOutput(p) || elsewhere(module, p) || hidden(module, p)) {
          continue;
        }
        String name = p.getFileName().toString();
        boolean compiled = COMPILED.stream().anyMatch(name::endsWith);
        if (compiled && roots.stream().anyMatch(p::startsWith)) {
          continue;
        }
        out.add(p);
      }
    }
    out.sort(null);
    return out;
  }

  private boolean inOutput(Path file) {
    for (Path at = file.getParent(); at != null && at.startsWith(project.root); at = at.getParent()) {
      if (Project.output(at)) {
        return true;
      }
    }
    return false;
  }

  private boolean elsewhere(Maven.Build module, Path file) {
    for (Maven.Build other : project.modules) {
      if (other != module
          && other.dir.startsWith(module.dir)
          && !other.dir.equals(module.dir)
          && file.startsWith(other.dir)) {
        return true;
      }
    }
    return false;
  }

  // A directory whose name starts with a dot is an editor's or a tool's, not the module's,
  // but for Maven's own.
  private static boolean hidden(Maven.Build module, Path file) {
    for (Path at = file.getParent(); at != null && !at.equals(module.dir); at = at.getParent()) {
      String name = at.getFileName().toString();
      if (name.startsWith(".") && !name.equals(".mvn")) {
        return true;
      }
    }
    return false;
  }

  private static List<Path> files(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return new ArrayList<>();
    }
    try (Stream<Path> all = Files.walk(dir)) {
      return all.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
    }
  }

  private String relative(Path file) {
    return project.root.relativize(file).toString().replace('\\', '/');
  }

  private static void line(MessageDigest h, String text) {
    h.update(text.getBytes(StandardCharsets.UTF_8));
    h.update((byte) '\n');
  }

  static String digest(Path file) throws IOException {
    MessageDigest h = sha256();
    byte[] buffer = new byte[1 << 16];
    try (InputStream in = Files.newInputStream(file)) {
      int n;
      while ((n = in.read(buffer)) >= 0) {
        h.update(buffer, 0, n);
      }
    }
    return hex(h.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    for (byte b : bytes) {
      out.append(String.format("%02x", b));
    }
    return out.toString();
  }
}
