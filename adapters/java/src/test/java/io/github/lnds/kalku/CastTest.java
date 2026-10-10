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
    // What `kalku init` leaves in a project is kalku's, not the project's.
    Files.createDirectories(root.resolve(".kalku"));
    Files.write(root.resolve(".kalku").resolve("summon"), "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
    Files.write(root.resolve(".kalku.toml"), "language = \"java\"\n".getBytes(StandardCharsets.UTF_8));
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
    return Talk.ask(requests);
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

  // What the baseline says of its run is so of this one: the copy it names is where the
  // project was built, without what version control keeps.
  @Test
  void theBaselineSaysHowItsRunWasUnlikeTheBuildsOwn() throws Exception {
    List<?> said = (List<?>) baseline.get("differences");
    assertEquals(5, said.size(), baseline.toString());
    Path built = reni.resolve("calc").resolve("work").resolve("0").resolve("project").toRealPath();
    assertTrue(((String) said.get(0)).contains("kept in the reni, " + built + ":"), said.toString());
    assertTrue(((String) said.get(1)).contains("not by surefire"), said.toString());
    assertTrue(((String) said.get(4)).contains("JAVA_HOME"), said.toString());
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
    // Nor does kalku's own configuration go into the copy that is built: a build that checks
    // every file for a licence header fails on a file that is not the project's.
    Path built = reni.resolve("calc").resolve("work").resolve("0").resolve("project");
    assertFalse(Files.exists(built.resolve(".kalku.toml")));
    assertFalse(Files.exists(built.resolve(".kalku")));
    assertTrue(Files.exists(built.resolve("pom.xml")));
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

  // ---- coverage --------------------------------------------------------------

  private static Map<String, List<?>> reaching(Map<?, ?> baselineDone) {
    Map<String, List<?>> by = new LinkedHashMap<>();
    for (Object each : (List<?>) baselineDone.get("coverage")) {
      Map<?, ?> entry = (Map<?, ?>) each;
      by.put(entry.get("file") + ":" + entry.get("line"), (List<?>) entry.get("tests"));
    }
    return by;
  }

  @Test
  void eachSiteIsGivenTheTestsThatReachIt() {
    Map<String, List<?>> by = reaching(baseline);
    assertEquals(Arrays.asList("fx.CalcTest#tenIsBigAndNineIsNot()"), by.get(CALC + ":12"));
    assertEquals(Arrays.asList("fx.CalcTest#clampsToTheLimit()"), by.get(CALC + ":16"));
    assertEquals(Arrays.asList("fx.CalcTest#zeroHasItsOwnLabel()"), by.get(CALC + ":24"));
    assertEquals(
        Arrays.asList("fx.CalcTest$Settings#theBuildsOwnSettingsReachTheTests()"),
        by.get(CALC + ":28"));
  }

  // The compiler gives a constant's line no code and copies its value into the classes that
  // use it. A line with no code is not a line no test reaches: it is one every test may
  // depend on, and a site there keeps the whole suite.
  @Test
  void aLineWithNoCodeOfItsOwnKeepsEveryTest() {
    Map<String, List<?>> by = reaching(baseline);
    assertEquals(new java.util.HashSet<>(all), new java.util.HashSet<>(by.get(CALC + ":4")));
    assertEquals(new java.util.HashSet<>(all), new java.util.HashSet<>(by.get(LIMITS + ":6")));
  }

  // What selecting tests must never change: the outcome. Every site of the project, cast
  // against the tests coverage names for it and against the whole suite.
  @Test
  void aCastAgainstTheTestsThatReachASiteEndsAsOneAgainstAllOfThem() throws Exception {
    Map<String, List<?>> by = reaching(baseline);
    List<String> selected = new ArrayList<>(Arrays.asList(hello(root), "{\"type\":\"prepare\",\"id\":2}"));
    List<String> whole = new ArrayList<>(selected);
    for (Map<?, ?> site : sites) {
      Map<?, ?> start = (Map<?, ?>) ((Map<?, ?>) site.get("span")).get("start");
      List<?> tests = by.get(site.get("file") + ":" + start.get("line"));
      assertNotNull(tests, "no test is named for " + site);
      List<String> names = new ArrayList<>();
      for (Object test : tests) {
        names.add((String) test);
      }
      selected.add(castOf(site, names));
      whole.add(castOf(site, all));
    }
    List<Map<?, ?>> narrow = ask(root, selected.toArray(new String[0]));
    List<Map<?, ?>> wide = ask(root, whole.toArray(new String[0]));
    assertEquals(sites.size() + 2, narrow.size());
    int killed = 0;
    for (int i = 2; i < narrow.size(); i++) {
      assertEquals("cast_done", narrow.get(i).get("type"), narrow.get(i).toString());
      assertEquals(wide.get(i).get("outcome"), narrow.get(i).get("outcome"), sites.get(i - 2).toString());
      killed += "killed".equals(narrow.get(i).get("outcome")) ? 1 : 0;
    }
    assertEquals(14, sites.size());
    assertEquals(9, killed);
  }

  // Past what a line may carry, the same entries go to a file, as one JSON array.
  @Test
  void coverageTooLargeForALineGoesToAFile() throws Exception {
    Path project = temp.resolve("spilled");
    copy(PROJECTS.resolve("calc"), project);
    String small = hello(project).replace("\"inline_limit_bytes\":65536", "\"inline_limit_bytes\":10");
    Map<?, ?> done =
        ask(project, small, "{\"type\":\"prepare\",\"id\":2}", "{\"type\":\"baseline\",\"id\":4}").get(2);
    assertNull(done.get("coverage"));
    Path file = Paths.get((String) done.get("coverage_path"));
    assertTrue(file.startsWith(reni.resolve("spilled")), file.toString());
    List<?> entries =
        (List<?>) Json.decode(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim());
    assertEquals(((List<?>) baseline.get("coverage")).size(), entries.size());
  }

  // A class fills its table when it is first used, in whichever test comes first, and only
  // that test would be credited with the lines that fill it. Here the first test does not
  // look at what the table holds and the second does: counted naively, the wekufe below would
  // be cast against the first alone and come back a survivor.
  @Test
  void whatAClassDoesWhenFirstUsedBelongsToEveryTest() throws Exception {
    Path project = temp.resolve("tables");
    copy(PROJECTS.resolve("tables"), project);
    String file = "src/main/java/fx/Codes.java";
    List<Map<?, ?>> said =
        ask(project, hello(project), "{\"type\":\"prepare\",\"id\":2}", sitesOf(file), "{\"type\":\"baseline\",\"id\":4}");
    assertEquals("green", said.get(3).get("status"), said.get(3).toString());
    Map<String, List<?>> by = reaching(said.get(3));
    Map<?, ?> inTable = null;
    Map<?, ?> inLabel = null;
    for (Object each : (List<?>) said.get(2).get("sites")) {
      Map<?, ?> site = (Map<?, ?>) each;
      if ("1".equals(site.get("original")) && "2".equals(site.get("replacement"))) {
        inTable = site;
      } else if ("\"many\"".equals(site.get("original"))) {
        inLabel = site;
      }
    }
    // The table is filled on line 14, and `label` returns on line 28, which only what runs
    // before every test reaches.
    for (Map<?, ?> site : Arrays.asList(inTable, inLabel)) {
      Map<?, ?> start = (Map<?, ?>) ((Map<?, ?>) site.get("span")).get("start");
      List<String> tests = new ArrayList<>();
      for (Object test : by.get(file + ":" + start.get("line"))) {
        tests.add((String) test);
      }
      assertEquals(3, tests.size(), site.toString());
      Map<?, ?> done =
          ask(project, hello(project), "{\"type\":\"prepare\",\"id\":2}", castOf(site, tests)).get(2);
      assertEquals("killed", done.get("outcome"), site + " " + done);
    }
    // Where the tests do tell which of them reaches a line, only that one is named.
    assertEquals(Arrays.asList("fx.CodesTest#aKnowsItsNames()"), by.get(file + ":20"));
  }

  // A suite that does not end the same way with the agent counting as without it: here a
  // test that looks for the agent and fails when it finds it. What was counted is then not
  // what the suite does, so nothing of it is reported, the reason is said, and a cast against
  // the whole suite still ends as it should.
  @Test
  void coverageThatCannotBeTrustedIsWithheldAndSaidSo() throws Exception {
    Path project = temp.resolve("watched");
    copy(PROJECTS.resolve("calc"), project);
    Files.write(
        project.resolve("src/test/java/fx/AgentTest.java"),
        ("package fx;\n"
                + "import static org.junit.jupiter.api.Assertions.assertFalse;\n"
                + "import java.lang.management.ManagementFactory;\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "class AgentTest {\n"
                + "  @Test\n"
                + "  void nothingIsWatching() {\n"
                + "    assertFalse(ManagementFactory.getRuntimeMXBean().getInputArguments().toString().contains(\"jacoco\"));\n"
                + "  }\n"
                + "}\n")
            .getBytes(StandardCharsets.UTF_8));
    java.io.PrintStream err = System.err;
    java.io.ByteArrayOutputStream said = new java.io.ByteArrayOutputStream();
    Map<?, ?> done;
    try {
      System.setErr(new java.io.PrintStream(said, true, "UTF-8"));
      done =
          ask(project, hello(project), "{\"type\":\"prepare\",\"id\":2}", "{\"type\":\"baseline\",\"id\":4}")
              .get(2);
    } finally {
      System.setErr(err);
    }
    assertEquals("green", done.get("status"), done.toString());
    assertEquals(6, ((List<?>) done.get("tests")).size());
    assertNull(done.get("coverage"));
    assertNull(done.get("coverage_path"));
    String why = new String(said.toByteArray(), StandardCharsets.UTF_8);
    assertTrue(why.contains("per-test coverage is not reportable"), why);
    assertTrue(why.contains("did not end the same way"), why);

    List<String> every = new ArrayList<>(all);
    every.add("fx.AgentTest#nothingIsWatching()");
    Map<?, ?> cast =
        ask(project, hello(project), "{\"type\":\"prepare\",\"id\":2}", castOf(site(CALC, ">=", ">"), every))
            .get(2);
    assertEquals("killed", cast.get("outcome"), cast.toString());
  }

  // ---- several modules ---------------------------------------------------------

  // `shop` is three modules: `core`; `app`, which uses it; and `audit`, which is nothing but
  // a test of `core` and has no code of its own. What `core` computes for a discount only
  // `app`'s tests look at, and `app` holds a copy of a constant of `core`'s.
  @Test
  void aSiteIsJudgedByTheTestsOfEveryModuleThatUsesIts() throws Exception {
    Path shop = temp.resolve("shop");
    copy(PROJECTS.resolve("shop"), shop);
    // What the user's own build left in a module is not what is measured.
    Path stale = shop.resolve("core").resolve("target").resolve("classes").resolve("stale.marker");
    Files.createDirectories(stale.getParent());
    Files.write(stale, new byte[] {1});

    String prices = "core/src/main/java/fx/core/Prices.java";
    String checkout = "app/src/main/java/fx/app/Checkout.java";
    List<Map<?, ?>> said =
        ask(shop, hello(shop), "{\"type\":\"prepare\",\"id\":2}", sitesOf(prices, checkout), "{\"type\":\"baseline\",\"id\":4}");
    assertEquals("prepared", said.get(1).get("type"), said.get(1).toString());
    assertEquals(2L, said.get(1).get("modules"));
    Map<?, ?> done = said.get(3);
    assertEquals("green", done.get("status"), done.toString());

    // A test says which module it is a test of, and its file is where that module keeps it.
    Map<String, String> files = new LinkedHashMap<>();
    for (Object each : (List<?>) done.get("tests")) {
      files.put((String) ((Map<?, ?>) each).get("test"), (String) ((Map<?, ?>) each).get("file"));
    }
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("core::fx.core.PricesTest#aPriceIsMoreThanNothing()", "core/src/test/java/fx/core/PricesTest.java");
    expected.put("app::fx.app.CheckoutTest#aHundredShipsFree()", "app/src/test/java/fx/app/CheckoutTest.java");
    expected.put(
        "app::fx.app.CheckoutTest#fiftyGetsTheDiscountAndFortyNineDoesNot()",
        "app/src/test/java/fx/app/CheckoutTest.java");
    expected.put("audit::fx.audit.PricesAuditTest#fiveIsAPrice()", "audit/src/test/java/fx/audit/PricesAuditTest.java");
    assertEquals(expected, files);
    List<String> every = new ArrayList<>(files.keySet());

    // A line of `core` that only a test of `app` reaches is credited to that test.
    Map<String, List<?>> by = reaching(done);
    assertEquals(
        Arrays.asList("app::fx.app.CheckoutTest#fiftyGetsTheDiscountAndFortyNineDoesNot()"),
        by.get(prices + ":10"));
    // And a line two modules' tests reach, to both.
    assertEquals(
        Arrays.asList(
            "audit::fx.audit.PricesAuditTest#fiveIsAPrice()",
            "core::fx.core.PricesTest#aPriceIsMoreThanNothing()"),
        by.get(prices + ":14"));
    assertEquals(new java.util.HashSet<>(every), new java.util.HashSet<>(by.get(prices + ":6")));

    List<Map<?, ?>> found = new ArrayList<>();
    for (Object each : (List<?>) said.get(2).get("sites")) {
      found.add((Map<?, ?>) each);
    }
    assertEquals(7, found.size(), found.toString());
    List<String> selected = new ArrayList<>(Arrays.asList(hello(shop), "{\"type\":\"prepare\",\"id\":2}"));
    List<String> whole = new ArrayList<>(selected);
    for (Map<?, ?> site : found) {
      Map<?, ?> start = (Map<?, ?>) ((Map<?, ?>) site.get("span")).get("start");
      List<String> names = new ArrayList<>();
      for (Object test : by.get(site.get("file") + ":" + start.get("line"))) {
        names.add((String) test);
      }
      selected.add(castOf(site, names));
      whole.add(castOf(site, every));
    }
    List<Map<?, ?>> narrow = ask(shop, selected.toArray(new String[0]));
    List<Map<?, ?>> wide = ask(shop, whole.toArray(new String[0]));
    for (int i = 0; i < found.size(); i++) {
      Map<?, ?> site = found.get(i);
      Map<?, ?> cast = narrow.get(i + 2);
      // Every site of this project is one a test notices, in its own module or the other.
      assertEquals("killed", cast.get("outcome"), site + " " + cast);
      assertEquals("killed", wide.get(i + 2).get("outcome"), site + " " + wide.get(i + 2));
      if (prices.equals(site.get("file")) && ">=".equals(site.get("original"))) {
        assertEquals("app::fx.app.CheckoutTest#fiftyGetsTheDiscountAndFortyNineDoesNot()", cast.get("killed_by"));
      }
      if ("100".equals(site.get("original"))) {
        // The constant is `core`'s, and the copy of it that a test notices is in `app`.
        assertEquals("dependents", site.get("reload"));
        assertEquals("app::fx.app.CheckoutTest#aHundredShipsFree()", cast.get("killed_by"));
      }
    }

    Path built = reni.resolve("shop").resolve("work").resolve("0").resolve("project");
    assertTrue(Files.isRegularFile(built.resolve("core/target/classes/fx/core/Prices.class")));
    assertFalse(Files.exists(built.resolve("core/target/classes/stale.marker")));
    assertFalse(Files.exists(shop.resolve("app").resolve("target")));
  }

  // ---- Gradle ------------------------------------------------------------------

  // Whether the `gradle` on this machine runs on the JDK these tests run on. No one version of
  // Gradle runs on every JDK the kalku does: 9 needs Java 17, and 8 does not know Java 25.
  private static boolean gradleRuns() {
    try {
      // A build, however empty: asked only for its version, Gradle answers on any JDK.
      Path empty = Files.createDirectories(temp.resolve("gradle-probe"));
      Files.write(empty.resolve("settings.gradle"), new byte[0]);
      ProcessBuilder builder =
          new ProcessBuilder("gradle", "--no-daemon", "-q", "help").directory(empty.toFile());
      builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
      builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
      return builder.start().waitFor() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  // A project of the Maven fixtures, with a Gradle build in place of its `pom.xml`.
  private static Path asGradle(String name) throws IOException {
    Path project = temp.resolve("gradle-" + name);
    copy(PROJECTS.resolve(name), project);
    try (Stream<Path> walk = Files.walk(project)) {
      for (Path pom : walk.filter(p -> p.getFileName().toString().equals("pom.xml")).collect(Collectors.toList())) {
        Files.delete(pom);
      }
    }
    Path build = PROJECTS.resolve("gradle").resolve(name);
    try (Stream<Path> walk = Files.walk(build)) {
      for (Path file : walk.filter(Files::isRegularFile).collect(Collectors.toList())) {
        Path to = project.resolve(build.relativize(file).toString());
        Files.createDirectories(to.getParent());
        Files.copy(file, to);
      }
    }
    return project;
  }

  // The same sources, built by Gradle instead of Maven: the same tests, the same map of which
  // test reaches what, and the same outcome for every site.
  @Test
  void aGradleBuildOfTheSameCodeGivesTheSameOutcomes() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(gradleRuns(), "no Gradle that runs on this JDK");
    Path project = asGradle("calc");
    List<String> before = tree(project);
    List<Map<?, ?>> said =
        ask(project, hello(project), "{\"type\":\"prepare\",\"id\":2}", sitesOf(CALC, LIMITS), "{\"type\":\"baseline\",\"id\":4}");
    assertEquals("prepared", said.get(1).get("type"), said.get(1).toString());
    Map<?, ?> done = said.get(3);
    assertEquals("green", done.get("status"), done.toString());
    List<String> ran = new ArrayList<>();
    for (Object test : (List<?>) done.get("tests")) {
      ran.add((String) ((Map<?, ?>) test).get("test"));
    }
    // The nested test only passes with the JVM argument and the system property the build
    // gives its tests: Gradle's `jvmArgs` and `systemProperty`, as Maven's `argLine`.
    assertEquals(new java.util.HashSet<>(all), new java.util.HashSet<>(ran));
    assertEquals(reaching(baseline), reaching(done));
    assertTrue(((List<?>) done.get("differences")).get(1).toString().contains("not by Gradle"));

    List<String> casts = new ArrayList<>(Arrays.asList(hello(project), "{\"type\":\"prepare\",\"id\":2}"));
    List<Map<?, ?>> found = new ArrayList<>();
    for (Object each : (List<?>) said.get(2).get("sites")) {
      found.add((Map<?, ?>) each);
      casts.add(castOf((Map<?, ?>) each, all));
    }
    assertEquals(14, found.size());
    List<Map<?, ?>> outcomes = ask(project, casts.toArray(new String[0]));
    int killed = 0;
    for (int i = 0; i < found.size(); i++) {
      assertEquals("cast_done", outcomes.get(i + 2).get("type"), outcomes.get(i + 2).toString());
      killed += "killed".equals(outcomes.get(i + 2).get("outcome")) ? 1 : 0;
    }
    assertEquals(9, killed);
    // Gradle writes `build` and `.gradle` beside the build file: in the reni, not here.
    assertEquals(before, tree(project));
  }

  // Two projects of one Gradle build, one using the other, which Gradle puts on the other's
  // class path as a jar. A site in the one is still noticed by the tests of the other, and a
  // constant of the one is still compiled again in the other.
  @Test
  void aGradleBuildOfSeveralProjectsIsJudgedAcrossThem() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(gradleRuns(), "no Gradle that runs on this JDK");
    Path project = asGradle("shop");
    String prices = "core/src/main/java/fx/core/Prices.java";
    String checkout = "app/src/main/java/fx/app/Checkout.java";
    List<Map<?, ?>> said =
        ask(project, hello(project), "{\"type\":\"prepare\",\"id\":2}", sitesOf(prices, checkout), "{\"type\":\"baseline\",\"id\":4}");
    assertEquals("prepared", said.get(1).get("type"), said.get(1).toString());
    Map<?, ?> done = said.get(3);
    assertEquals("green", done.get("status"), done.toString());
    List<String> every = new ArrayList<>();
    for (Object test : (List<?>) done.get("tests")) {
      every.add((String) ((Map<?, ?>) test).get("test"));
    }
    assertEquals(4, every.size(), every.toString());
    assertEquals(
        Arrays.asList("app::fx.app.CheckoutTest#fiftyGetsTheDiscountAndFortyNineDoesNot()"),
        reaching(done).get(prices + ":10"));

    List<String> casts = new ArrayList<>(Arrays.asList(hello(project), "{\"type\":\"prepare\",\"id\":2}"));
    List<Map<?, ?>> found = new ArrayList<>();
    for (Object each : (List<?>) said.get(2).get("sites")) {
      found.add((Map<?, ?>) each);
      casts.add(castOf((Map<?, ?>) each, every));
    }
    assertEquals(7, found.size());
    List<Map<?, ?>> outcomes = ask(project, casts.toArray(new String[0]));
    for (int i = 0; i < found.size(); i++) {
      Map<?, ?> cast = outcomes.get(i + 2);
      assertEquals("killed", cast.get("outcome"), found.get(i) + " " + cast);
      if ("100".equals(found.get(i).get("original"))) {
        assertEquals("app::fx.app.CheckoutTest#aHundredShipsFree()", cast.get("killed_by"));
      }
    }
  }

  // ---- JUnit 4 and TestNG --------------------------------------------------------

  // `calc`'s own sources, with the build and the tests of another fixture in place of its own.
  private static Path testedWith(String fixture, String as) throws IOException {
    Path project = temp.resolve(as);
    copy(PROJECTS.resolve("calc").resolve("src").resolve("main"), project.resolve("src").resolve("main"));
    copy(PROJECTS.resolve(fixture), project);
    return project;
  }

  // The outcome of every site of `calc` under this project's tests, cast against the tests
  // coverage names for it; and what the baseline said, for whoever wants to look.
  private static int killedIn(Path project, List<String> expected) throws Exception {
    List<Map<?, ?>> said =
        ask(project, hello(project), "{\"type\":\"prepare\",\"id\":2}", sitesOf(CALC, LIMITS), "{\"type\":\"baseline\",\"id\":4}");
    assertEquals("prepared", said.get(1).get("type"), said.get(1).toString());
    Map<?, ?> done = said.get(3);
    assertEquals("green", done.get("status"), done.toString());
    List<String> ran = new ArrayList<>();
    for (Object test : (List<?>) done.get("tests")) {
      ran.add((String) ((Map<?, ?>) test).get("test"));
    }
    Collections.sort(ran);
    assertEquals(expected, ran);
    // These tests are JUnit 4's or TestNG's, and the baseline says what ran them.
    List<?> unlike = (List<?>) done.get("differences");
    assertTrue(unlike.get(unlike.size() - 1).toString().contains("an engine fetched"), unlike.toString());
    Map<String, List<?>> by = reaching(done);
    List<String> casts = new ArrayList<>(Arrays.asList(hello(project), "{\"type\":\"prepare\",\"id\":2}"));
    List<Map<?, ?>> found = new ArrayList<>();
    for (Object each : (List<?>) said.get(2).get("sites")) {
      Map<?, ?> site = (Map<?, ?>) each;
      Map<?, ?> start = (Map<?, ?>) ((Map<?, ?>) site.get("span")).get("start");
      List<String> names = new ArrayList<>();
      for (Object test : by.get(site.get("file") + ":" + start.get("line"))) {
        names.add((String) test);
      }
      found.add(site);
      casts.add(castOf(site, names));
    }
    assertEquals(14, found.size());
    List<Map<?, ?>> outcomes = ask(project, casts.toArray(new String[0]));
    int killed = 0;
    for (int i = 0; i < found.size(); i++) {
      assertEquals("cast_done", outcomes.get(i + 2).get("type"), found.get(i) + " " + outcomes.get(i + 2));
      killed += "killed".equals(outcomes.get(i + 2).get("outcome")) ? 1 : 0;
    }
    return killed;
  }

  // Tests written for JUnit 4, in a project that has nothing of the JUnit Platform. The
  // engine that runs them on it is fetched for the project, and the same code under the same
  // tests, written the older way, gives the same outcomes. One of the test classes is JUnit
  // 4's way of running a test with several sets of values: it is one test, as a method is.
  @Test
  void testsWrittenForJUnit4AreRunThroughItsEngine() throws Exception {
    int killed =
        killedIn(
            testedWith("junit4", "with-junit4"),
            Arrays.asList(
                "fx.AddsTest#adds()",
                "fx.CalcTest#clampsToTheLimit()",
                "fx.CalcTest#tenIsBigAndNineIsNot()",
                "fx.CalcTest#theBuildsOwnSettingsReachTheTests()",
                "fx.CalcTest#zeroHasItsOwnLabel()"));
    assertEquals(9, killed);
  }

  @Test
  void testsWrittenForTestNGAreRunThroughItsEngine() throws Exception {
    int killed =
        killedIn(
            testedWith("testng", "with-testng"),
            Arrays.asList(
                "fx.CalcTest#adds(int, int, int)",
                "fx.CalcTest#clampsToTheLimit()",
                "fx.CalcTest#tenIsBigAndNineIsNot()",
                "fx.CalcTest#theBuildsOwnSettingsReachTheTests()",
                "fx.CalcTest#zeroHasItsOwnLabel()"));
    assertEquals(9, killed);
  }

  // And built by Gradle, which runs JUnit 4 when a build does not say otherwise.
  @Test
  void aGradleBuildThatTestsWithJUnit4IsMeasuredToo() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(gradleRuns(), "no Gradle that runs on this JDK");
    Path project = testedWith("junit4", "gradle-junit4");
    Files.delete(project.resolve("pom.xml"));
    for (String file : Arrays.asList("build.gradle", "settings.gradle")) {
      Files.copy(PROJECTS.resolve("gradle").resolve("junit4").resolve(file), project.resolve(file));
    }
    int killed =
        killedIn(
            project,
            Arrays.asList(
                "fx.AddsTest#adds()",
                "fx.CalcTest#clampsToTheLimit()",
                "fx.CalcTest#tenIsBigAndNineIsNot()",
                "fx.CalcTest#theBuildsOwnSettingsReachTheTests()",
                "fx.CalcTest#zeroHasItsOwnLabel()"));
    assertEquals(9, killed);
  }

  // What the baseline of this project ran, and the outcome of the wekufe that empties the
  // label of zero, cast against all of it. The project tests with JUnit 4 and with TestNG, and
  // only its TestNG test looks at a label.
  private static void bothJudge(Path project) throws Exception {
    List<Map<?, ?>> said =
        ask(project, hello(project), "{\"type\":\"prepare\",\"id\":2}", sitesOf(CALC), "{\"type\":\"baseline\",\"id\":4}");
    assertEquals("prepared", said.get(1).get("type"), said.get(1).toString());
    Map<?, ?> done = said.get(3);
    assertEquals("green", done.get("status"), done.toString());
    List<String> ran = new ArrayList<>();
    for (Object test : (List<?>) done.get("tests")) {
      ran.add((String) ((Map<?, ?>) test).get("test"));
    }
    Collections.sort(ran);
    assertEquals(Arrays.asList("fx.AddsTest#adds()", "fx.LabelTest#zeroHasItsOwnLabel()"), ran);
    Map<?, ?> label = null;
    for (Object each : (List<?>) said.get(2).get("sites")) {
      Map<?, ?> site = (Map<?, ?>) each;
      if ("\"zero\"".equals(site.get("original")) && "\"\"".equals(site.get("replacement"))) {
        label = site;
      }
    }
    assertNotNull(label, said.get(2).toString());
    Map<?, ?> cast =
        ask(project, hello(project), "{\"type\":\"prepare\",\"id\":2}", castOf(label, ran)).get(2);
    assertEquals("killed", cast.get("outcome"), cast.toString());
    assertEquals("fx.LabelTest#zeroHasItsOwnLabel()", cast.get("killed_by"));
  }

  // A project with both frameworks gets both engines, so the tests of each judge a wekufe.
  @Test
  void aProjectWithJUnit4AndTestNGHasTheTestsOfBothRun() throws Exception {
    bothJudge(testedWith("both", "with-both"));
  }

  @Test
  void aGradleBuildWithJUnit4AndTestNGHasTheTestsOfBothRun() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(gradleRuns(), "no Gradle that runs on this JDK");
    Path project = testedWith("both", "gradle-both");
    Files.delete(project.resolve("pom.xml"));
    for (String file : Arrays.asList("build.gradle", "settings.gradle")) {
      Files.copy(PROJECTS.resolve("gradle").resolve("both").resolve(file), project.resolve(file));
    }
    bothJudge(project);
  }

  // The engine that runs JUnit 4 needs 4.12. An older one is said, with its number.
  @Test
  void aJUnit4TooOldForItsEngineIsRefusedByItsVersion() throws Exception {
    Path project = testedWith("junit4", "with-junit-4-11");
    Path pom = project.resolve("pom.xml");
    String text = new String(Files.readAllBytes(pom), StandardCharsets.UTF_8);
    assertTrue(text.contains("<version>4.13.2</version>"));
    Files.write(pom, text.replace("<version>4.13.2</version>", "<version>4.11</version>").getBytes(StandardCharsets.UTF_8));
    Map<?, ?> said = ask(project, hello(project), "{\"type\":\"prepare\",\"id\":2}").get(1);
    assertEquals("prepare_failed", said.get("code"), said.toString());
    assertTrue(((String) said.get("message")).contains("JUnit 4.11"), said.toString());
    assertTrue(((String) said.get("message")).contains("4.12 or later"), said.toString());
  }

  // ---- what a build says of its tests ---------------------------------------------

  private static final Map<Path, List<Map<?, ?>>> SUITES = new LinkedHashMap<>();
  private static final String BIG = "fx.CalcTest#tenIsBigAndNineIsNot()";
  private static final String BROKEN = "fx.BrokenTest#isLeftOutByTheBuild()";
  private static final String CHECK = "fx.LabelCheck#aNumberThatIsNotZeroIsSome()";
  private static final String ENVIRONMENT = "fx.CalcTest#theBuildsEnvironmentReachesTheTests()";
  private static final String FLAKY = "fx.FlakyTest#passesTheSecondTime()";

  // `calc` under the build of `suite`, which says which classes are its tests and what they
  // run with: by Maven, or by Gradle.
  private static Path suite(boolean gradle) throws IOException {
    Path project = temp.resolve(gradle ? "gradle-suite" : "with-suite");
    if (!Files.exists(project)) {
      testedWith("suite", project.getFileName().toString());
      if (gradle) {
        Files.delete(project.resolve("pom.xml"));
        for (String file : Arrays.asList("build.gradle", "settings.gradle")) {
          Files.copy(PROJECTS.resolve("gradle").resolve("suite").resolve(file), project.resolve(file));
        }
      }
    }
    return project;
  }

  // What the kalku said of it: prepared, its sites, and its baseline. Asked once.
  private static List<Map<?, ?>> measured(Path project) throws Exception {
    if (!SUITES.containsKey(project)) {
      SUITES.put(
          project,
          ask(project, hello(project), "{\"type\":\"prepare\",\"id\":2}", sitesOf(CALC), "{\"type\":\"baseline\",\"id\":4}"));
    }
    List<Map<?, ?>> said = SUITES.get(project);
    assertEquals("prepared", said.get(1).get("type"), said.get(1).toString());
    assertEquals("baseline_done", said.get(3).get("type"), said.get(3).toString());
    return said;
  }

  // The tests a baseline names under `key`: the ones that ran, or the ones that failed.
  private static List<String> named(Map<?, ?> baseline, String key) {
    List<String> out = new ArrayList<>();
    for (Object each : (List<?>) baseline.get(key)) {
      out.add((String) ((Map<?, ?>) each).get("test"));
    }
    Collections.sort(out);
    return out;
  }

  private static Map<?, ?> siteIn(List<Map<?, ?>> said, String original, String replacement) {
    for (Object each : (List<?>) said.get(2).get("sites")) {
      Map<?, ?> site = (Map<?, ?>) each;
      if (original.equals(site.get("original")) && replacement.equals(site.get("replacement"))) {
        return site;
      }
    }
    throw new AssertionError("no site turning " + original + " into " + replacement);
  }

  private static Map<?, ?> castIn(Path project, Map<?, ?> site, List<String> tests) throws Exception {
    return ask(project, hello(project), "{\"type\":\"prepare\",\"id\":2}", castOf(site, tests)).get(2);
  }

  // A class named the way a test is, which the build excludes because it fails. Run, it would
  // call a suite red that the build calls green.
  @Test
  void aClassTheBuildLeavesOutIsNotRun() throws Exception {
    Map<?, ?> done = measured(suite(false)).get(3);
    assertFalse(named(done, "tests").contains(BROKEN), done.toString());
    assertFalse(named(done, "failures").contains(BROKEN), done.toString());
  }

  // A class the build includes under a name that is no test's by default. Only it looks at
  // the label of a number that is not zero: left out, the wekufe it notices is a survivor.
  @Test
  void aClassTheBuildIncludesIsRunWhateverItIsCalled() throws Exception {
    Path project = suite(false);
    List<Map<?, ?>> said = measured(project);
    assertTrue(named(said.get(3), "tests").contains(CHECK), said.get(3).toString());
    Map<?, ?> cast = castIn(project, siteIn(said, "\"some\"", "\"\""), Arrays.asList(BIG, CHECK));
    assertEquals("killed", cast.get("outcome"), cast.toString());
    assertEquals(CHECK, cast.get("killed_by"));
  }

  @Test
  void whatTheBuildSetsInTheEnvironmentReachesTheTests() throws Exception {
    Map<?, ?> done = measured(suite(false)).get(3);
    assertTrue(named(done, "tests").contains(ENVIRONMENT), done.toString());
    assertFalse(named(done, "failures").contains(ENVIRONMENT), done.toString());
  }

  // The build runs a failed test again, and calls it failed only when it fails every time.
  // So does the baseline, and so does a cast: a test that passes the second time has passed,
  // and one that a wekufe makes fail every time has still killed it.
  @Test
  void aTestTheBuildRunsAgainHasFailedOnlyWhenItFailsEveryTime() throws Exception {
    Path project = suite(false);
    List<Map<?, ?>> said = measured(project);
    Map<?, ?> done = said.get(3);
    assertEquals(Arrays.asList(BIG, ENVIRONMENT, FLAKY, CHECK), named(done, "tests"));
    assertEquals(Collections.emptyList(), named(done, "failures"));
    assertEquals("green", done.get("status"), done.toString());

    Map<?, ?> unnoticed = castIn(project, siteIn(said, "\"some\"", "\"\""), Arrays.asList(FLAKY));
    assertEquals("survived", unnoticed.get("outcome"), unnoticed.toString());
    Map<?, ?> noticed = castIn(project, siteIn(said, ">=", ">"), named(done, "tests"));
    assertEquals("killed", noticed.get("outcome"), noticed.toString());
    assertEquals(BIG, noticed.get("killed_by"));
  }

  // Gradle takes every class for a candidate when a build names none, and holds its patterns
  // against the class files as they are written.
  @Test
  void aGradleBuildsOwnPatternsAndEnvironmentAreItsTests() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(gradleRuns(), "no Gradle that runs on this JDK");
    Path project = suite(true);
    List<Map<?, ?>> said = measured(project);
    Map<?, ?> done = said.get(3);
    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertFalse(named(done, "tests").contains(BROKEN), done.toString()),
        () -> assertTrue(named(done, "tests").contains(CHECK), done.toString()),
        () -> assertFalse(named(done, "failures").contains(ENVIRONMENT), done.toString()),
        () -> assertEquals(Arrays.asList(BIG, ENVIRONMENT, CHECK), named(done, "tests")),
        () -> assertEquals("green", done.get("status"), done.toString()));
    Map<?, ?> cast = castIn(project, siteIn(said, "\"some\"", "\"\""), Arrays.asList(BIG, CHECK));
    assertEquals("killed", cast.get("outcome"), cast.toString());
    assertEquals(CHECK, cast.get("killed_by"));
  }

  // A pattern kalku does not read is not guessed at: the build is refused, by the pattern.
  @Test
  void aPatternItDoesNotReadIsRefusedByName() throws Exception {
    Path project = testedWith("suite", "with-a-regex");
    Path pom = project.resolve("pom.xml");
    String text = new String(Files.readAllBytes(pom), StandardCharsets.UTF_8);
    assertTrue(text.contains("<include>fx.*Check</include>"));
    Files.write(
        pom,
        text.replace("<include>fx.*Check</include>", "<include>%regex[.*Check.*]</include>")
            .getBytes(StandardCharsets.UTF_8));
    Map<?, ?> said = ask(project, hello(project), "{\"type\":\"prepare\",\"id\":2}").get(1);
    assertEquals("prepare_failed", said.get("code"), said.toString());
    assertTrue(((String) said.get("message")).contains("`%regex[.*Check.*]`"), said.toString());
    assertTrue(((String) said.get("message")).contains("surefire's `includes`"), said.toString());
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

  // A build that takes long is not a kalku that has gone quiet. Here the build tool is a
  // script that says what it is fetching, takes its time, and fails: what it said is on
  // stderr while it is still running, where whoever waits can read it, and is what the
  // failure quotes.
  @Test
  void whatABuildSaysWhileItRunsIsPassedOn() throws Exception {
    Path project = temp.resolve("slow");
    copy(PROJECTS.resolve("calc"), project);
    Path maven = temp.resolve("slow-mvn");
    Files.write(
        maven,
        ("#!/bin/sh\necho 'Downloading the whole world'\nsleep 7\n"
                + "echo '[ERROR] the world did not arrive'\nexit 1\n")
            .getBytes(StandardCharsets.UTF_8));
    Files.setPosixFilePermissions(
        maven, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
    String hello =
        hello(project).replace("\"env\":{}", "\"env\":{\"KALKU_MAVEN\":" + Json.encode(maven.toString()) + "}");
    java.io.PrintStream err = System.err;
    java.io.ByteArrayOutputStream said = new java.io.ByteArrayOutputStream();
    Map<?, ?> failed;
    try {
      System.setErr(new java.io.PrintStream(said, true, "UTF-8"));
      failed = ask(project, hello, "{\"type\":\"prepare\",\"id\":2}").get(1);
    } finally {
      System.setErr(err);
    }
    assertEquals("prepare_failed", failed.get("code"), failed.toString());
    assertTrue(((String) failed.get("message")).contains("the world did not arrive"), failed.toString());
    String heard = new String(said.toByteArray(), StandardCharsets.UTF_8);
    assertTrue(heard.contains("Downloading the whole world"), heard);
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
  void aProjectWithNoCodeOfItsOwnIsRefusedByName() throws Exception {
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
    assertTrue(((String) said.get("message")).contains("no module of this project has code"), said.toString());
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
