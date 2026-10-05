package io.github.lnds.kalku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A real project, built by Maven in a reni and measured: `prepare`, `baseline`, and wekufe
 * whose outcome is known because the project was written for them.
 *
 * <p>The project is `src/test/projects/calc`. Every outcome asserted here is one a reader can
 * check against its eight lines of tests.
 */
class CastTest {
  private static final Path PROJECTS = Paths.get("src", "test", "projects");
  private static final String CALC = "src/main/java/fx/Calc.java";
  private static final String LIMITS = "src/main/java/fx/Limits.java";

  @TempDir static Path temp;
  static Path root;
  static Path reni;
  static List<String> treeBefore;
  static Map<?, ?> prepared;
  static Map<?, ?> baseline;
  static List<Map<?, ?>> sites = new ArrayList<>();
  static List<String> all = new ArrayList<>();

  @BeforeAll
  static void measured() throws Exception {
    root = temp.resolve("calc");
    reni = temp.resolve("reni");
    copy(PROJECTS.resolve("calc"), root);
    treeBefore = tree(root);

    List<Map<?, ?>> said =
        ask(root, hello(root), "{\"type\":\"prepare\",\"id\":2}", sitesOf(CALC, LIMITS), "{\"type\":\"baseline\",\"id\":4}");
    prepared = said.get(1);
    for (Object site : (List<?>) said.get(2).get("sites")) {
      sites.add((Map<?, ?>) site);
    }
    baseline = said.get(3);
    if (baseline.get("tests") != null) {
      for (Object test : (List<?>) baseline.get("tests")) {
        all.add((String) ((Map<?, ?>) test).get("test"));
      }
    }
  }

  // ---- driving the kalku ---------------------------------------------------

  static void copy(Path from, Path to) throws IOException {
    try (Stream<Path> walk = Files.walk(from)) {
      for (Path p : walk.collect(Collectors.toList())) {
        Path relative = from.relativize(p);
        // A build someone ran in the fixture is not part of it.
        if (relative.getNameCount() > 0 && relative.getName(0).toString().equals("target")) {
          continue;
        }
        Path target = to.resolve(relative.toString());
        if (Files.isDirectory(p)) {
          Files.createDirectories(target);
        } else {
          Files.copy(p, target);
        }
      }
    }
  }

  static List<String> tree(Path dir) throws IOException {
    try (Stream<Path> walk = Files.walk(dir)) {
      return walk.map(p -> dir.relativize(p).toString() + (Files.isRegularFile(p) ? " " + size(p) : ""))
          .sorted()
          .collect(Collectors.toList());
    }
  }

  private static long size(Path p) {
    try {
      return Files.size(p);
    } catch (IOException e) {
      return -1;
    }
  }

  static List<Map<?, ?>> ask(Path project, String... requests) throws Exception {
    byte[] in = (String.join("\n", requests) + "\n").getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    new Service(new ByteArrayInputStream(in), out, "test").serve();
    List<Map<?, ?>> said = new ArrayList<>();
    for (String line : new String(out.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
      said.add((Map<?, ?>) Json.decode(line));
    }
    return said;
  }

  static String hello(Path project) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("type", "hello");
    o.put("id", 1L);
    o.put("protocol", 1L);
    o.put("root", project.toString());
    o.put("reni", reni.resolve(project.getFileName().toString()).toString());
    o.put("worker", 0L);
    o.put("inline_limit_bytes", 65536L);
    o.put("env", new LinkedHashMap<String, Object>());
    return Json.encode(o);
  }

  static String sitesOf(String... files) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("type", "sites");
    o.put("id", 3L);
    o.put("files", Arrays.asList(files));
    o.put("spells", Spell.CAST);
    o.put("exclude_calls", new ArrayList<String>());
    return Json.encode(o);
  }

  // The one site of a file that turns `original` into `replacement`.
  static Map<?, ?> site(String file, String original, String replacement) {
    List<Map<?, ?>> found =
        sites.stream()
            .filter(
                s ->
                    file.equals(s.get("file"))
                        && original.equals(s.get("original"))
                        && replacement.equals(s.get("replacement")))
            .collect(Collectors.toList());
    assertEquals(1, found.size(), "sites turning " + original + " into " + replacement + " in " + file);
    return found.get(0);
  }

  static String castOf(Map<?, ?> site, List<String> tests) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("type", "cast");
    o.put("id", 5L);
    o.put("wekufe", site.get("site_id"));
    o.put("site", site);
    o.put("tests", tests);
    return Json.encode(o);
  }

  // What a cast of this site against these tests is answered with, in a kalku that was only
  // prepared: a kalku in a pool is never asked for a baseline.
  static Map<?, ?> cast(Map<?, ?> site, List<String> tests) throws Exception {
    List<Map<?, ?>> said =
        ask(root, hello(root), "{\"type\":\"prepare\",\"id\":2}", castOf(site, tests));
    assertEquals("prepared", said.get(1).get("type"), said.get(1).toString());
    return said.get(2);
  }

  static Map<?, ?> cast(String file, String original, String replacement) throws Exception {
    return cast(site(file, original, replacement), all);
  }

  // The same site, with something else written in its place.
  static Map<String, Object> rewritten(Map<?, ?> site, String replacement) {
    Map<String, Object> other = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : site.entrySet()) {
      other.put((String) entry.getKey(), entry.getValue());
    }
    other.put("replacement", replacement);
    return other;
  }

  // ---- prepare and baseline --------------------------------------------------

  @Test
  void theProjectIsBuiltAndItsSourcesCounted() {
    assertEquals("prepared", prepared.get("type"), prepared.toString());
    assertEquals(2L, prepared.get("modules"));
  }

  @Test
  void theBaselineIsGreenAndNamesEveryTestByItsMethod() {
    assertEquals("baseline_done", baseline.get("type"), baseline.toString());
    assertEquals("green", baseline.get("status"), baseline.toString());
    assertEquals(
        Arrays.asList(
            "fx.CalcTest#adds(int, int, int)",
            "fx.CalcTest#clampsToTheLimit()",
            "fx.CalcTest#tenIsBigAndNineIsNot()",
            "fx.CalcTest#zeroHasItsOwnLabel()",
            "fx.CalcTest$Settings#theBuildsOwnSettingsReachTheTests()"),
        all.stream().sorted().collect(Collectors.toList()));
    for (Object test : (List<?>) baseline.get("tests")) {
      // A nested class is written in the file of the class around it.
      assertEquals("src/test/java/fx/CalcTest.java", ((Map<?, ?>) test).get("file"));
    }
    assertEquals(Collections.emptyList(), baseline.get("failures"));
  }

  // The fixture's nested test only passes with the JVM argument and the system property the
  // project's build gives its tests. It is in the list above; this says why that matters.
  @Test
  void theTestsRunWithWhatTheBuildGivesThem() {
    assertTrue(all.contains("fx.CalcTest$Settings#theBuildsOwnSettingsReachTheTests()"));
  }

  @Test
  void nothingIsWrittenInTheProject() throws Exception {
    assertEquals(treeBefore, tree(root));
    assertTrue(Files.isDirectory(reni.resolve("calc").resolve("work").resolve("0").resolve("project").resolve("target")));
  }

  // ---- outcomes --------------------------------------------------------------

  @Test
  void aWekufeATestNoticesIsKilledAndTheTestIsNamed() throws Exception {
    Map<?, ?> done = cast(CALC, ">=", ">");
    assertEquals("killed", done.get("outcome"), done.toString());
    assertEquals("fx.CalcTest#tenIsBigAndNineIsNot()", done.get("killed_by"));
    assertEquals(false, done.get("dirty"));
  }

  @Test
  void aWekufeNoTestNoticesSurvives() throws Exception {
    Map<?, ?> done = cast(CALC, "\"some\"", "\"\"");
    assertEquals("survived", done.get("outcome"), done.toString());
    assertNull(done.get("killed_by"));
  }

  // The compiler copies `Limits.CAP` into `Calc`. Compiling `Limits` alone would leave the old
  // bound in `Calc`, and a wekufe a test does notice would come back as a survivor.
  @Test
  void aConstantChangesInEveryClassThatUsesIt() throws Exception {
    Map<?, ?> cap = site(LIMITS, "10", "11");
    assertEquals("dependents", cap.get("reload"));
    Map<?, ?> done = cast(cap, all);
    assertEquals("killed", done.get("outcome"), done.toString());
    assertEquals("fx.CalcTest#tenIsBigAndNineIsNot()", done.get("killed_by"));
  }

  // And into the tests. This one names the constant, so with any limit it passes; a test
  // class left as it was compiled would still hold the old 5 and fail for no reason.
  @Test
  void aConstantChangesInTheTestsThatNameIt() throws Exception {
    Map<?, ?> done = cast(CALC, "5", "6");
    assertEquals("survived", done.get("outcome"), done.toString());
  }

  @Test
  void aWekufeThatDoesNotCompileSaysWhatTheCompilerSaid() throws Exception {
    Map<?, ?> done = cast(rewritten(site(CALC, "\"zero\"", "\"\""), "0"), all);
    assertEquals("compile_error", done.get("outcome"), done.toString());
    assertTrue(((String) done.get("message")).startsWith(CALC + ":"), done.toString());
  }

  // `fx.Account` calls a method an annotation processor writes. The wekufe is compiled with
  // the processors the build names, or the call would not resolve and every wekufe in the
  // file would look like one that does not compile.
  @Test
  void aWekufeIsCompiledWithTheProcessorsTheBuildNames() throws Exception {
    Map<?, ?> said = castInGenerated(temp.resolve("generated"), false);
    assertEquals("killed", said.get("outcome"), said.toString());
  }

  // The same project, with the processor named the way a build that inherits its versions
  // names it: without one. Maven knows the version, and so does what Maven resolved.
  @Test
  void aProcessorNamedWithoutItsVersionIsFoundAllTheSame() throws Exception {
    Map<?, ?> said = castInGenerated(temp.resolve("unversioned"), true);
    assertEquals("killed", said.get("outcome"), said.toString());
  }

  // What this kalku reads of the build can be wrong, or not enough. Here it is made wrong on
  // purpose, in the reni, after a build that worked: a language level no compiler has. The
  // file then does not compile even unchanged. That is this kalku failing to reproduce the
  // build, and it says so: a `compile_error` would be a wekufe counted as caught.
  @Test
  void aBuildItCannotReproduceIsNeverBlamedOnTheWekufe() throws Exception {
    Path project = temp.resolve("misread");
    copy(PROJECTS.resolve("calc"), project);
    assertEquals(
        "prepared",
        ask(project, hello(project), "{\"type\":\"prepare\",\"id\":2}").get(1).get("type"));
    Path resolved =
        reni.resolve("misread").resolve("work").resolve("0").resolve("project").resolve("target").resolve("kalku.pom.xml");
    String text = new String(Files.readAllBytes(resolved), StandardCharsets.UTF_8);
    String level = "<maven.compiler.release>11</maven.compiler.release>";
    assertTrue(text.contains(level));
    Files.write(
        resolved,
        text.replace(level, "<maven.compiler.release>99</maven.compiler.release>")
            .getBytes(StandardCharsets.UTF_8));

    for (Map<?, ?> site : Arrays.asList(site(CALC, ">=", ">"), site(LIMITS, "10", "11"))) {
      List<Map<?, ?>> said =
          ask(project, hello(project), "{\"type\":\"prepare\",\"id\":2}", castOf(site, all));
      assertEquals("prepared", said.get(1).get("type"), said.get(1).toString());
      assertEquals("cast_failed", said.get(2).get("code"), said.get(2).toString());
      assertEquals(true, said.get(2).get("fatal"));
      assertTrue(((String) said.get(2).get("message")).contains("even unchanged"), said.get(2).toString());
    }
  }

  // A cast that changes nothing has to survive: the same text, compiled and run the way a
  // wekufe is. If it did not, the difference would be between how this kalku compiles and
  // runs and how the build does, and every kill it reported would be in doubt.
  @Test
  void aCastThatChangesNothingSurvives() throws Exception {
    Map<?, ?> inOneFile = site(CALC, ">=", ">");
    Map<?, ?> inAConstant = site(LIMITS, "10", "11");
    for (Map<?, ?> site : Arrays.asList(inOneFile, inAConstant)) {
      Map<?, ?> done = cast(rewritten(site, (String) site.get("original")), all);
      assertEquals("survived", done.get("outcome"), site.get("reload") + ": " + done);
    }
  }

  @Test
  void aCastThatChangesNothingSurvivesWhereAProcessorWritesCode() throws Exception {
    Map<?, ?> done = castInGenerated(temp.resolve("generated-unchanged"), false, ">=");
    assertEquals("survived", done.get("outcome"), done.toString());
  }

  // A cast of the one comparison in `fx.Account`, which becomes `becomes`. With
  // `unversioned`, the processor is named without its version and the project manages it.
  private static Map<?, ?> castInGenerated(Path project, boolean unversioned) throws Exception {
    return castInGenerated(project, unversioned, ">");
  }

  private static Map<?, ?> castInGenerated(Path project, boolean unversioned, String becomes)
      throws Exception {
    copy(PROJECTS.resolve("generated"), project);
    if (unversioned) {
      Path pom = project.resolve("pom.xml");
      String text = new String(Files.readAllBytes(pom), StandardCharsets.UTF_8);
      String path = "<artifactId>lombok</artifactId>\n              <version>${lombok.version}</version>";
      assertTrue(text.contains(path));
      text =
          text.replace(path, "<artifactId>lombok</artifactId>")
              .replace(
                  "<dependencies>",
                  "<dependencyManagement><dependencies><dependency>"
                      + "<groupId>org.projectlombok</groupId><artifactId>lombok</artifactId>"
                      + "<version>${lombok.version}</version></dependency></dependencies>"
                      + "</dependencyManagement>\n  <dependencies>");
      Files.write(pom, text.getBytes(StandardCharsets.UTF_8));
    }
    String file = "src/main/java/fx/Account.java";
    List<Map<?, ?>> found = ask(project, hello(project), sitesOf(file));
    Map<?, ?> site = null;
    for (Object each : (List<?>) found.get(1).get("sites")) {
      if (">=".equals(((Map<?, ?>) each).get("original"))) {
        site = (Map<?, ?>) each;
      }
    }
    List<Map<?, ?>> said =
        ask(
            project,
            hello(project),
            "{\"type\":\"prepare\",\"id\":2}",
            castOf(
                rewritten(site, becomes),
                Collections.singletonList("fx.AccountTest#aHundredIsRich()")));
    assertEquals("prepared", said.get(1).get("type"), said.get(1).toString());
    return said.get(2);
  }

  // ---- what is not a verdict ---------------------------------------------------

  // A JVM that ends before its tests do did not fail an assertion. `System.exit` is the
  // plainest way there; running out of memory is another.
  @Test
  void aTestJvmThatEndsEarlyIsNeverAKill() throws Exception {
    String exits = "((java.util.function.Supplier<String>) () -> { System.exit(0); return \"\"; }).get()";
    Map<?, ?> said = cast(rewritten(site(CALC, "\"zero\"", "\"\""), exits), all);
    assertEquals("error", said.get("type"), said.toString());
    assertEquals("cast_failed", said.get("code"));
    assertEquals(true, said.get("fatal"));
    assertTrue(((String) said.get("message")).contains("before the tests were over"), said.toString());
  }

  @Test
  void noTestsRunIsNotASurvivor() throws Exception {
    Map<?, ?> said = cast(site(CALC, "\"some\"", "\"\""), Collections.emptyList());
    assertEquals("error", said.get("type"), said.toString());
    assertEquals("cast_failed", said.get("code"));
  }

  @Test
  void aTestItDoesNotKnowIsRefused() throws Exception {
    Map<?, ?> said =
        cast(site(CALC, "\"some\"", "\"\""), Collections.singletonList("fx.CalcTest#nowhere()"));
    assertEquals("unknown_test", said.get("code"), said.toString());
  }

  @Test
  void aSiteFoundInOtherTextIsRefused() throws Exception {
    Map<String, Object> stale = rewritten(site(CALC, ">=", ">"), ">");
    stale.put("original", "<=");
    Map<?, ?> said = cast(stale, all);
    assertEquals("bad_request", said.get("code"), said.toString());
  }

  @Test
  void aCastBeforePrepareIsRefused() throws Exception {
    Map<?, ?> said = ask(root, hello(root), castOf(site(CALC, ">=", ">"), all)).get(1);
    assertEquals("not_ready", said.get("code"), said.toString());
  }

  // The runner is compiled in the reni against the JUnit the project has, so there is no
  // version the kalku was built for. 5.4 is the oldest that has the one artifact projects
  // depend on; 6 needs Java 17.
  @Test
  void theRunnerFitsWhateverJUnitTheProjectHas() throws Exception {
    List<String> versions = new ArrayList<>(Collections.singletonList("5.4.2"));
    if (Runtime.version().feature() >= 17) {
      versions.add("6.0.0");
    }
    for (String version : versions) {
      Path other = temp.resolve("junit-" + version);
      copy(PROJECTS.resolve("calc"), other);
      Path pom = other.resolve("pom.xml");
      String text = new String(Files.readAllBytes(pom), StandardCharsets.UTF_8);
      assertTrue(text.contains("<version>5.11.4</version>"));
      Files.write(
          pom,
          text.replace("<version>5.11.4</version>", "<version>" + version + "</version>")
              .getBytes(StandardCharsets.UTF_8));
      List<Map<?, ?>> said =
          ask(other, hello(other), "{\"type\":\"prepare\",\"id\":2}", "{\"type\":\"baseline\",\"id\":4}");
      assertEquals("prepared", said.get(1).get("type"), version + ": " + said.get(1));
      assertEquals("green", said.get(2).get("status"), version + ": " + said.get(2));
      assertEquals(5, ((List<?>) said.get(2).get("tests")).size(), version);
    }
  }

  // ---- projects that cannot be measured ----------------------------------------

  @Test
  void aProjectThatDoesNotCompileSaysWhatTheCompilerSaid() throws Exception {
    Path broken = temp.resolve("broken");
    copy(PROJECTS.resolve("calc"), broken);
    Files.write(
        broken.resolve("src/main/java/fx/Broken.java"),
        "package fx;\nclass Broken { int f() { return \"no\"; } }\n".getBytes(StandardCharsets.UTF_8));
    Map<?, ?> said = ask(broken, hello(broken), "{\"type\":\"prepare\",\"id\":2}").get(1);
    assertEquals("prepare_failed", said.get("code"), said.toString());
    assertEquals(true, said.get("fatal"));
    assertTrue(((String) said.get("message")).contains("Broken.java"), said.toString());
  }

  @Test
  void aProjectOfSeveralModulesIsRefusedByName() throws Exception {
    Path reactor = temp.resolve("reactor");
    Files.createDirectories(reactor);
    Files.write(
        reactor.resolve("pom.xml"),
        ("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                + "<groupId>fx</groupId><artifactId>reactor</artifactId><version>1</version>"
                + "<packaging>pom</packaging></project>")
            .getBytes(StandardCharsets.UTF_8));
    Map<?, ?> said = ask(reactor, hello(reactor), "{\"type\":\"prepare\",\"id\":2}").get(1);
    assertEquals("prepare_failed", said.get("code"), said.toString());
    assertTrue(((String) said.get("message")).contains("several modules"), said.toString());
  }

  @Test
  void aProjectWithoutAPomIsRefusedByName() throws Exception {
    Path bare = temp.resolve("bare");
    Files.createDirectories(bare.resolve("src"));
    Map<?, ?> said = ask(bare, hello(bare), "{\"type\":\"prepare\",\"id\":2}").get(1);
    assertEquals("prepare_failed", said.get("code"), said.toString());
    assertNotNull(said.get("message"));
    assertFalse(((String) said.get("message")).isEmpty());
  }
}
