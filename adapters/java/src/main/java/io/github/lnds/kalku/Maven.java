package io.github.lnds.kalku;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * What Maven knows about a project, asked of Maven.
 *
 * <p>Maven builds the copy in the reni and writes down two things beside it: the class path the
 * tests run with, and the project as Maven itself resolved it, with every inherited setting
 * filled in. Nothing about a build is guessed from the pom as written.
 *
 * <p>Everything Maven prints goes to a file. The kalku's standard output is the protocol.
 */
final class Maven {
  private Maven() {}

  /** A project that could not be built, or is not one this kalku measures yet. */
  static final class Failed extends Exception {
    private static final long serialVersionUID = 1L;

    Failed(String why) {
      super(why);
    }
  }

  /** A built project, as a cast needs to know it. */
  static final class Build {
    Path project;
    // The jars the tests run with, the project's own classes left out.
    List<Path> libraries = new ArrayList<>();
    Path classes;
    Path testClasses;
    Path sources;
    Path testSources;
    // What the build tells `javac`, so a wekufe is compiled the way the project is.
    List<String> compilerFlags = new ArrayList<>();
    // What the build tells the JVM its tests run in.
    List<String> jvmFlags = new ArrayList<>();
    // The JUnit Platform the project's engines were written for.
    String platform;
    // Where the launcher of that same version is, when the project does not bring it.
    Path launcher;
  }

  // Pinned, so what these goals write does not change under the kalku.
  private static final String DEPENDENCY = "org.apache.maven.plugins:maven-dependency-plugin:3.8.1";
  private static final String HELP = "org.apache.maven.plugins:maven-help-plugin:3.5.1";

  private static final String CLASSPATH_FILE = "target/kalku.classpath";
  private static final String POM_FILE = "target/kalku.pom.xml";
  private static final String JDK_FILE = "target/kalku.jdk";

  private static final Pattern ENGINE = Pattern.compile("junit-platform-engine-(.+)\\.jar");

  /**
   * Builds the copy, tests included but not run, and reads back what Maven resolved.
   *
   * @param fresh false when nothing changed since a build that is still there: Maven is slow
   *     to start, and what it would write is already written
   */
  static Build build(Path project, Path lib, Map<String, String> env, boolean fresh)
      throws Failed, IOException {
    Path classpath = project.resolve(CLASSPATH_FILE);
    Path pom = project.resolve(POM_FILE);
    // Classes built by one JDK are not trusted under another: a newer one wrote a format an
    // older one cannot load.
    Path builtBy = project.resolve(JDK_FILE);
    String jdk = System.getProperty("java.version", "") + " " + System.getProperty("java.home", "");
    boolean sameJdk =
        Files.isRegularFile(builtBy)
            && jdk.equals(new String(Files.readAllBytes(builtBy), StandardCharsets.UTF_8));
    if (fresh || !sameJdk || !Files.isRegularFile(classpath) || !Files.isRegularFile(pom)) {
      // Maven goes by times, and would keep what the other JDK compiled.
      boolean otherJdk = Files.isRegularFile(builtBy) && !sameJdk;
      Files.deleteIfExists(builtBy);
      run(
          project,
          env,
          otherJdk ? "clean" : "-DskipTests",
          "-DskipTests",
          "test-compile",
          DEPENDENCY + ":build-classpath",
          "-Dmdep.outputFile=" + CLASSPATH_FILE,
          HELP + ":effective-pom",
          "-Doutput=" + POM_FILE);
      Files.write(builtBy, jdk.getBytes(StandardCharsets.UTF_8));
    }
    Build build = new Build();
    build.project = project;
    readClasspath(build, classpath);
    readPom(build, pom);
    launcher(build, lib, env);
    return build;
  }

  // ---- running Maven ---------------------------------------------------------

  private static void run(Path project, Map<String, String> env, String... goals)
      throws Failed, IOException {
    List<String> command = new ArrayList<>();
    command.add(executable(project, env));
    command.add("-B");
    command.add("-q");
    command.addAll(Arrays.asList(goals));
    Path log = project.resolve("target").resolve("kalku.maven.log");
    Files.createDirectories(log.getParent());
    ProcessBuilder builder = new ProcessBuilder(command).directory(project.toFile());
    builder.environment().clear();
    builder.environment().putAll(env);
    builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
    builder.redirectErrorStream(true).redirectOutput(log.toFile());
    int exit;
    try {
      exit = builder.start().waitFor();
    } catch (IOException e) {
      throw new Failed(
          "cannot run Maven (`"
              + command.get(0)
              + "`): "
              + e.getMessage()
              + ". Put `mvn` on the PATH, add the Maven wrapper to the project, or set KALKU_MAVEN.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Failed("interrupted while Maven was running");
    }
    if (exit != 0) {
      throw new Failed(said(log, exit));
    }
  }

  // The project's own wrapper pins the Maven its build was written for.
  private static String executable(Path project, Map<String, String> env) {
    String chosen = env.get("KALKU_MAVEN");
    if (chosen != null && !chosen.isEmpty()) {
      return chosen;
    }
    Path wrapper = project.resolve("mvnw");
    return Files.isExecutable(wrapper) ? wrapper.toString() : "mvn";
  }

  // The compiler's own words, which Maven marks `[ERROR]`, without Maven's advice about them.
  private static String said(Path log, int exit) throws IOException {
    List<String> errors = new ArrayList<>();
    for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
      if (line.startsWith("[ERROR]")) {
        String text = line.substring("[ERROR]".length()).trim();
        if (text.startsWith("To see the full stack trace") || text.startsWith("-> [Help")) {
          break;
        }
        if (!text.isEmpty()) {
          errors.add(text);
        }
      }
    }
    if (errors.isEmpty()) {
      return "Maven exited with " + exit + " and said nothing marked as an error; see " + log;
    }
    return String.join("\n", errors.subList(0, Math.min(errors.size(), 20)));
  }

  // ---- reading what it wrote -------------------------------------------------

  private static void readClasspath(Build build, Path file) throws IOException {
    String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
    for (String entry : text.split(Pattern.quote(File.pathSeparator))) {
      if (!entry.isEmpty()) {
        Path jar = Paths.get(entry);
        build.libraries.add(jar);
        Matcher engine = ENGINE.matcher(jar.getFileName().toString());
        if (engine.matches()) {
          build.platform = engine.group(1);
        }
        if (jar.getFileName().toString().startsWith("junit-platform-launcher-")) {
          build.launcher = jar;
        }
      }
    }
  }

  private static void readPom(Build build, Path file) throws Failed, IOException {
    Element project;
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      Document document = factory.newDocumentBuilder().parse(file.toFile());
      project = document.getDocumentElement();
    } catch (Exception e) {
      throw new Failed("cannot read what Maven resolved (" + file + "): " + e.getMessage());
    }
    // A reactor is written as several projects, and one that only gathers modules as `pom`.
    if (!project.getTagName().equals("project")
        || "pom".equals(text(project, "packaging"))
        || child(project, "modules") != null) {
      throw new Failed(
          "this is a Maven project of several modules, which kalku does not measure yet; "
              + "it measures a project with one `pom.xml` and its own sources");
    }
    if (build.platform == null) {
      throw new Failed(
          "no JUnit Platform engine is among the project's test dependencies; kalku runs tests "
              + "through the JUnit Platform (JUnit 5 or later), and a project with JUnit 4 or "
              + "TestNG alone is not measured yet");
    }
    Element b = child(project, "build");
    build.classes = Paths.get(text(b, "outputDirectory"));
    build.testClasses = Paths.get(text(b, "testOutputDirectory"));
    build.sources = Paths.get(text(b, "sourceDirectory"));
    build.testSources = Paths.get(text(b, "testSourceDirectory"));
    Element properties = child(project, "properties");
    compiler(build, plugin(b, "maven-compiler-plugin"), properties);
    surefire(build, plugin(b, "maven-surefire-plugin"));
  }

  // The settings a build can state as the plugin's own, or as the property it defaults to.
  private static void compiler(Build build, Element plugin, Element properties) {
    Element set = configuration(plugin, "default-compile");
    String release = setting(set, "release", properties, "maven.compiler.release");
    String source = setting(set, "source", properties, "maven.compiler.source");
    String target = setting(set, "target", properties, "maven.compiler.target");
    if (release != null) {
      build.compilerFlags.addAll(Arrays.asList("--release", release));
    } else {
      if (source != null) {
        build.compilerFlags.addAll(Arrays.asList("-source", source));
      }
      if (target != null) {
        build.compilerFlags.addAll(Arrays.asList("-target", target));
      }
    }
    String encoding = setting(set, "encoding", properties, "project.build.sourceEncoding");
    if (encoding != null) {
      build.compilerFlags.addAll(Arrays.asList("-encoding", encoding));
    }
    if ("true".equals(setting(set, "parameters", properties, "maven.compiler.parameters"))) {
      build.compilerFlags.add("-parameters");
    }
    String proc = setting(set, "proc", properties, "maven.compiler.proc");
    if (proc != null) {
      build.compilerFlags.add("-proc:" + proc);
    }
    if ("true".equals(setting(set, "enablePreview", properties, "maven.compiler.enablePreview"))) {
      build.compilerFlags.add("--enable-preview");
    }
    Element args = child(set, "compilerArgs");
    for (Element arg : children(args)) {
      kept(build, text(arg));
    }
    String one = text(set, "compilerArgument");
    if (one != null) {
      for (String arg : one.trim().split("\\s+")) {
        kept(build, arg);
      }
    }
    // A program compiled with preview features only runs where they are switched on.
    if (build.compilerFlags.contains("--enable-preview")) {
      build.jvmFlags.add("--enable-preview");
    }
  }

  // What a build asks of its warnings is not asked of a wekufe: one file compiled alone does
  // not warn the way the whole project does, and a warning is not what a cast measures.
  private static void kept(Build build, String arg) {
    if (arg != null
        && !arg.isEmpty()
        && !arg.equals("-Werror")
        && !arg.startsWith("-Xlint")
        && !build.compilerFlags.contains(arg)) {
      build.compilerFlags.add(arg);
    }
  }

  private static void surefire(Build build, Element plugin) {
    Element set = configuration(plugin, "default-test");
    String argLine = text(set, "argLine");
    if (argLine != null) {
      for (String arg : argLine.trim().split("\\s+")) {
        // What another plugin fills in while the tests start is not known here.
        if (!arg.isEmpty() && !arg.contains("@{") && !arg.contains("${")) {
          build.jvmFlags.add(arg);
        }
      }
    }
    for (Element property : children(child(set, "systemPropertyVariables"))) {
      build.jvmFlags.add("-D" + property.getTagName() + "=" + text(property));
    }
  }

  // ---- the launcher ----------------------------------------------------------

  // Engines are on the project's class path; what starts them is not, because the build tool
  // brings its own. It has to be the version the engines were written for, so it is Maven that
  // fetches it, once, into the reni.
  private static void launcher(Build build, Path lib, Map<String, String> env)
      throws Failed, IOException {
    if (build.launcher != null) {
      return;
    }
    Path jar = lib.resolve("junit-platform-launcher-" + build.platform + ".jar");
    if (!Files.isRegularFile(jar)) {
      Files.createDirectories(lib);
      run(
          build.project,
          env,
          DEPENDENCY + ":copy",
          "-Dartifact=org.junit.platform:junit-platform-launcher:" + build.platform,
          "-DoutputDirectory=" + lib);
      if (!Files.isRegularFile(jar)) {
        throw new Failed("Maven did not fetch junit-platform-launcher " + build.platform);
      }
    }
    build.launcher = jar;
  }

  // ---- XML, as little of it as this needs --------------------------------------

  private static Element child(Element parent, String name) {
    if (parent == null) {
      return null;
    }
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element && ((Element) n).getTagName().equals(name)) {
        return (Element) n;
      }
    }
    return null;
  }

  private static List<Element> children(Element parent) {
    List<Element> out = new ArrayList<>();
    if (parent != null) {
      for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
        if (n instanceof Element) {
          out.add((Element) n);
        }
      }
    }
    return out;
  }

  private static String text(Element element) {
    return element == null ? null : element.getTextContent().trim();
  }

  private static String text(Element parent, String name) {
    return text(child(parent, name));
  }

  private static Element plugin(Element build, String artifact) {
    for (Element plugin : children(child(build, "plugins"))) {
      if (artifact.equals(text(plugin, "artifactId"))) {
        return plugin;
      }
    }
    return null;
  }

  // A plugin's settings as its default execution has them, which is where Maven writes the
  // plugin's own once it has resolved the project; the plugin's own otherwise.
  private static Element configuration(Element plugin, String execution) {
    for (Element e : children(child(plugin, "executions"))) {
      if (execution.equals(text(e, "id")) && child(e, "configuration") != null) {
        return child(e, "configuration");
      }
    }
    return child(plugin, "configuration");
  }

  private static String setting(Element set, String name, Element properties, String property) {
    String stated = text(set, name);
    return stated != null && !stated.isEmpty() ? stated : text(properties, property);
  }
}
