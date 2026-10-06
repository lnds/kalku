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
    List<String> testCompilerFlags = new ArrayList<>();
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

  // A build often checks more than that the code compiles: licence headers, style, the
  // version of Maven, what the repository's history says. Those checks are about the
  // project as its owner keeps it, and the copy in the reni is not that: it has no history
  // and it is only ever compiled. They are switched off by the names their plugins give.
  private static final String[] CHECKS =
      {
        "rat.skip", "checkstyle.skip", "enforcer.skip", "spotless.check.skip", "license.skip",
        "pmd.skip", "cpd.skip", "spotbugs.skip", "animal.sniffer.skip", "jacoco.skip",
        "maven.javadoc.skip", "maven.gitcommitid.skip", "japicmp.skip", "cyclonedx.skip",
        "sortpom.skip", "formatter.skip", "impsort.skip"
      };

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
      List<String> goals = new ArrayList<>();
      if (otherJdk) {
        goals.add("clean");
      }
      goals.add("-DskipTests");
      for (String check : CHECKS) {
        goals.add("-D" + check + "=true");
      }
      goals.addAll(
          Arrays.asList(
              "test-compile",
              DEPENDENCY + ":build-classpath",
              "-Dmdep.outputFile=" + CLASSPATH_FILE,
              HELP + ":effective-pom",
              "-Doutput=" + POM_FILE));
      run(project, env, goals.toArray(new String[0]));
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
    run(project, project, env, goals);
  }

  // Maven run in `where`, as the project `of` would run it: with its wrapper, if it has one.
  private static void run(Path where, Path of, Map<String, String> env, String... goals)
      throws Failed, IOException {
    Path project = where;
    List<String> command = new ArrayList<>();
    command.add(executable(of, env));
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
      String text = readable(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
      Document document =
          factory
              .newDocumentBuilder()
              .parse(new org.xml.sax.InputSource(new java.io.StringReader(text)));
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
    compiler(build, project, plugin(b, "maven-compiler-plugin"));
    surefire(build, plugin(b, "maven-surefire-plugin"));
  }

  /**
   * What Maven wrote as the resolved project, made into XML a parser accepts. It is not always:
   *
   * <ul>
   *   <li>Maven writes its own header and then the project, and the XML declaration can come
   *       out a second time, in the middle, where no parser allows one. Only the first is kept.
   *   <li>A property can have a name no XML element may have, {@code some.name?}, and Maven
   *       writes it out as one all the same. No setting read here is called that; the tags are
   *       dropped.
   * </ul>
   */
  static String readable(String text) {
    int first = text.indexOf("<?xml");
    if (first >= 0) {
      int after = text.indexOf("?>", first) + 2;
      text = text.substring(0, after) + text.substring(after).replaceAll("<\\?xml[^>]*\\?>", "");
    }
    return text.replaceAll("</?[A-Za-z_][\\w.\\-]*[^\\w.\\-\\s>/:][^<>\\s/]*>", "");
  }

  // What the build tells the compiler. A setting can be the plugin's own or the property it
  // defaults to; an argument the build passes as written is kept as written and in its order,
  // because an option and its value are two arguments and the same option may come twice.
  private static void compiler(Build build, Element project, Element plugin) {
    Element properties = child(project, "properties");
    Element set = configuration(plugin, "default-compile");
    List<String> stated = stated(set);
    List<String> flags = build.compilerFlags;
    if (!stated.contains("--release") && !stated.contains("-source")) {
      flags.addAll(
          level(
              setting(set, "release", properties, "maven.compiler.release"),
              setting(set, "source", properties, "maven.compiler.source"),
              setting(set, "target", properties, "maven.compiler.target")));
    }
    String encoding = setting(set, "encoding", properties, "project.build.sourceEncoding");
    if (encoding != null && !stated.contains("-encoding")) {
      flags.addAll(Arrays.asList("-encoding", encoding));
    }
    if ("true".equals(setting(set, "parameters", properties, "maven.compiler.parameters"))
        && !stated.contains("-parameters")) {
      flags.add("-parameters");
    }
    String proc = setting(set, "proc", properties, "maven.compiler.proc");
    if (proc != null && stated.stream().noneMatch(a -> a.startsWith("-proc:"))) {
      flags.add("-proc:" + proc);
    }
    if ("true".equals(setting(set, "enablePreview", properties, "maven.compiler.enablePreview"))
        && !stated.contains("--enable-preview")) {
      flags.add("--enable-preview");
    }
    flags.addAll(stated);
    if (!stated.contains("-processorpath") && !stated.contains("--processor-path")) {
      processors(build, project, child(set, "annotationProcessorPaths"));
    }
    // A program compiled with preview features only runs where they are switched on.
    if (flags.contains("--enable-preview")) {
      build.jvmFlags.add("--enable-preview");
    }

    // The tests are compiled as the sources are, unless the build gives them a level of
    // their own.
    Element tests = configuration(plugin, "default-testCompile");
    List<String> testLevel =
        level(
            setting(tests, "testRelease", properties, "maven.compiler.testRelease"),
            setting(tests, "testSource", properties, "maven.compiler.testSource"),
            setting(tests, "testTarget", properties, "maven.compiler.testTarget"));
    if (testLevel.isEmpty()) {
      build.testCompilerFlags.addAll(flags);
    } else {
      build.testCompilerFlags.addAll(testLevel);
      build.testCompilerFlags.addAll(withoutLevel(flags));
    }
  }

  private static List<String> level(String release, String source, String target) {
    List<String> out = new ArrayList<>();
    if (release != null) {
      out.addAll(Arrays.asList("--release", release));
    } else {
      if (source != null) {
        out.addAll(Arrays.asList("-source", source));
      }
      if (target != null) {
        out.addAll(Arrays.asList("-target", target));
      }
    }
    return out;
  }

  private static List<String> withoutLevel(List<String> flags) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < flags.size(); i++) {
      String flag = flags.get(i);
      if (flag.equals("--release") || flag.equals("-source") || flag.equals("-target")) {
        i++;
      } else {
        out.add(flag);
      }
    }
    return out;
  }

  // The arguments a build passes to the compiler as written. What it asks of its warnings is
  // left out: one file compiled alone does not warn the way the whole project does, and a
  // warning is not what a cast measures.
  private static List<String> stated(Element set) {
    List<String> out = new ArrayList<>();
    for (Element arg : children(child(set, "compilerArgs"))) {
      out.add(text(arg));
    }
    String one = text(set, "compilerArgument");
    if (one != null) {
      out.addAll(Arrays.asList(one.trim().split("\\s+")));
    }
    out.removeIf(a -> a == null || a.isEmpty() || a.equals("-Werror") || a.startsWith("-Xlint"));
    return out;
  }

  // Annotation processors a build names apart from its dependencies. Each is the jar Maven
  // put in the repository the dependencies came from; its version is the one stated, or the
  // one the project manages, which is how a build that inherits its versions names it.
  //
  // What a processor itself depends on is not looked for. A processor that needs more than
  // its own jar fails to load, the file then does not compile even unchanged, and a cast says
  // that rather than blame a wekufe.
  private static void processors(Build build, Element project, Element paths) {
    Path repository = repository(build);
    List<String> jars = new ArrayList<>();
    for (Element path : children(paths)) {
      String group = text(path, "groupId");
      String artifact = text(path, "artifactId");
      String version = text(path, "version");
      if (version == null || version.isEmpty()) {
        version = managed(project, group, artifact);
      }
      Path jar = null;
      if (repository != null && group != null && artifact != null && version != null) {
        jar =
            repository
                .resolve(group.replace('.', '/'))
                .resolve(artifact)
                .resolve(version)
                .resolve(artifact + "-" + version + ".jar");
      }
      if (jar == null || !Files.isRegularFile(jar)) {
        jar = among(build.libraries, artifact);
      }
      if (jar != null) {
        jars.add(jar.toString());
      }
    }
    if (!jars.isEmpty()) {
      build.compilerFlags.add("-processorpath");
      build.compilerFlags.add(String.join(File.pathSeparator, jars));
    }
  }

  // The version a project gives an artifact where it manages versions, or where it depends
  // on it; Maven has already resolved both.
  private static String managed(Element project, String group, String artifact) {
    List<Element> known = new ArrayList<>();
    known.addAll(children(child(child(project, "dependencyManagement"), "dependencies")));
    known.addAll(children(child(project, "dependencies")));
    for (Element dependency : known) {
      if (artifact != null
          && artifact.equals(text(dependency, "artifactId"))
          && (group == null || group.equals(text(dependency, "groupId")))
          && text(dependency, "version") != null) {
        return text(dependency, "version");
      }
    }
    return null;
  }

  private static Path among(List<Path> libraries, String artifact) {
    for (Path jar : libraries) {
      String name = jar.getFileName().toString();
      if (artifact != null && name.startsWith(artifact + "-") && name.endsWith(".jar")) {
        return jar;
      }
    }
    return null;
  }

  // The local repository, told from where the JUnit engine's jar is inside it.
  private static Path repository(Build build) {
    for (Path jar : build.libraries) {
      Matcher engine = ENGINE.matcher(jar.getFileName().toString());
      if (engine.matches()) {
        Path at = jar.getParent();
        for (String part :
            new String[] {engine.group(1), "junit-platform-engine", "platform", "junit", "org"}) {
          if (at == null || !at.getFileName().toString().equals(part)) {
            return null;
          }
          at = at.getParent();
        }
        return at;
      }
    }
    return null;
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

  // ---- what reads coverage ----------------------------------------------------

  /** The agent that counts what runs, and what reads its counts afterwards. */
  static final class Coverage {
    Path agent;
    List<Path> readers = new ArrayList<>();
  }

  // The one version of JaCoCo this kalku asks for. A JDK newer than it knows is one whose
  // classes it cannot count in, and coverage is then withheld rather than guessed.
  static final String JACOCO = "0.8.15";

  /**
   * Fetches the coverage agent and its reader into the reni, once.
   *
   * <p>Neither is the kalku's dependency and neither goes on the class path of the project's
   * tests. They are named in a pom of their own, so that Maven brings what they need as well.
   */
  static Coverage coverage(Build build, Path lib, Map<String, String> env)
      throws Failed, IOException {
    Path dir = lib.resolve("coverage-" + JACOCO);
    Path jars = dir.resolve("jars");
    Path agent = jars.resolve("org.jacoco.agent-" + JACOCO + "-runtime.jar");
    if (!Files.isRegularFile(agent)) {
      Files.createDirectories(dir);
      String pom =
          "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
              + "<groupId>kalku</groupId><artifactId>coverage</artifactId><version>1</version>"
              + "<packaging>pom</packaging><dependencies>"
              + "<dependency><groupId>org.jacoco</groupId><artifactId>org.jacoco.core</artifactId>"
              + "<version>" + JACOCO + "</version></dependency>"
              + "<dependency><groupId>org.jacoco</groupId><artifactId>org.jacoco.agent</artifactId>"
              + "<version>" + JACOCO + "</version><classifier>runtime</classifier></dependency>"
              + "</dependencies></project>\n";
      Files.write(dir.resolve("pom.xml"), pom.getBytes(StandardCharsets.UTF_8));
      run(dir, build.project, env, DEPENDENCY + ":copy-dependencies", "-DoutputDirectory=" + jars);
      if (!Files.isRegularFile(agent)) {
        throw new Failed("Maven did not fetch the JaCoCo agent " + JACOCO);
      }
    }
    Coverage coverage = new Coverage();
    coverage.agent = agent;
    try (java.util.stream.Stream<Path> all = Files.list(jars)) {
      all.filter(p -> p.toString().endsWith(".jar") && !p.equals(agent))
          .sorted()
          .forEach(coverage.readers::add);
    }
    return coverage;
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
