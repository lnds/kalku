package io.github.lnds.kalku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A warm kalku told what changed in the project: the next cast and the next baseline run what
 * the project holds now, not what it held when the kalku built it.
 *
 * <p>The project is `src/test/projects/calc`, copied for each test, since each one edits it.
 */
class ReloadTest {
  private static final Path CALC = Paths.get("src", "test", "projects", "calc");
  private static final String LIMITS = "src/main/java/fx/Limits.java";
  private static final String TEST = "src/test/java/fx/CalcTest.java";

  @TempDir Path temp;

  private Path project() throws Exception {
    Path root = temp.resolve("calc");
    CastTest.copy(CALC, root);
    return root;
  }

  private String hello(Path root) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("type", "hello");
    o.put("id", 1L);
    o.put("protocol", 1L);
    o.put("root", root.toString());
    o.put("reni", temp.resolve("reni").toString());
    o.put("worker", 0L);
    o.put("inline_limit_bytes", 65536L);
    o.put("env", new LinkedHashMap<String, Object>());
    return Json.encode(o);
  }

  private static String reload(String... files) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("type", "reload");
    o.put("id", 5L);
    o.put("files", Arrays.asList(files));
    return Json.encode(o);
  }

  private static void edit(Path root, String file, String from, String to) throws Exception {
    Path path = root.resolve(file);
    String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    assertTrue(text.contains(from), from + " in " + file);
    Files.write(path, text.replace(from, to).getBytes(StandardCharsets.UTF_8));
  }

  private static List<String> testsOf(Map<?, ?> baseline) {
    List<String> out = new ArrayList<>();
    for (Object test : (List<?>) baseline.get("tests")) {
      out.add((String) ((Map<?, ?>) test).get("test"));
    }
    return out;
  }

  private static Map<?, ?> prepared(Talk.Conversation kalku, Path root, ReloadTest t)
      throws Exception {
    assertEquals("ready", kalku.ask(t.hello(root)).get("type"));
    Map<?, ?> prepared = kalku.ask("{\"type\":\"prepare\",\"id\":2}");
    assertEquals("prepared", prepared.get("type"), prepared.toString());
    return prepared;
  }

  // A constant is copied into every class that reads it, so a kalku that compiled only the
  // file that changed would test `big` against the old bound, and the suite would be red.
  @Test
  void anEditedConstantReachesTheClassesThatReadItAndANewTestIsListed() throws Exception {
    Path root = project();
    try (Talk.Conversation kalku = new Talk.Conversation()) {
      prepared(kalku, root, this);
      Map<?, ?> before = kalku.ask("{\"type\":\"baseline\",\"id\":3}");
      assertEquals("green", before.get("status"), before.toString());

      edit(root, LIMITS, "CAP = 10", "CAP = 11");
      edit(root, TEST, "assertTrue(calc.big(10));\n    assertFalse(calc.big(9));",
          "assertTrue(calc.big(11));\n    assertFalse(calc.big(10));");
      edit(root, TEST, "  @Test\n  void zeroHasItsOwnLabel()",
          "  @Test\n  void oneHasAnotherLabel() {\n    assertEquals(\"some\", calc.label(1));\n  }\n\n"
              + "  @Test\n  void zeroHasItsOwnLabel()");
      Map<?, ?> reloaded = kalku.ask(reload(LIMITS, TEST));
      assertEquals("reloaded", reloaded.get("type"), reloaded.toString());
      assertEquals(Arrays.asList(LIMITS, TEST), reloaded.get("modules"));

      Map<?, ?> after = kalku.ask("{\"type\":\"baseline\",\"id\":6}");
      assertEquals("green", after.get("status"), after.toString());
      assertTrue(testsOf(after).contains("fx.CalcTest#oneHasAnotherLabel()"), testsOf(after).toString());
      assertEquals(testsOf(before).size() + 1, testsOf(after).size());

      // The site is found in the file as it is now, and its wekufe is cast from it.
      Map<?, ?> cap = null;
      for (Object each : (List<?>) kalku.ask(CastTest.sitesOf(LIMITS)).get("sites")) {
        if ("11".equals(((Map<?, ?>) each).get("original"))) {
          cap = (Map<?, ?>) each;
        }
      }
      assertTrue(cap != null, "a site on the edited constant");
      Map<?, ?> cast = kalku.ask(castOf(cap, testsOf(after)));
      assertEquals("killed", cast.get("outcome"), cast.toString());
      assertEquals("fx.CalcTest#tenIsBigAndNineIsNot()", cast.get("killed_by"));
    }
  }

  private static String castOf(Map<?, ?> site, List<String> tests) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("type", "cast");
    o.put("id", 8L);
    o.put("wekufe", site.get("site_id"));
    o.put("site", site);
    o.put("tests", tests);
    return Json.encode(o);
  }

  // An edit that does not compile is no project to cast in: the kalku says so and is ended,
  // and one summoned after it builds the project with its own build and says why.
  @Test
  void anEditThatDoesNotCompileEndsTheKalkuAndSaysWhy() throws Exception {
    Path root = project();
    try (Talk.Conversation kalku = new Talk.Conversation()) {
      prepared(kalku, root, this);
      edit(root, LIMITS, "static final int CAP = 10;", "static final int CAP = ;");
      Map<?, ?> said = kalku.ask(reload(LIMITS));
      assertEquals("error", said.get("type"), said.toString());
      assertEquals("reload_failed", said.get("code"));
      assertEquals(true, said.get("fatal"));
      assertTrue(((String) said.get("message")).contains("Limits.java"), said.toString());
    }
  }

  // A file the project did not have is the build's to take in: it is built again, and what
  // the new file brings is there for the next baseline.
  @Test
  void aNewFileIsBuiltByTheBuildAndItsTestsListed() throws Exception {
    Path root = project();
    try (Talk.Conversation kalku = new Talk.Conversation()) {
      prepared(kalku, root, this);
      Files.write(
          root.resolve("src/main/java/fx/Twice.java"),
          "package fx;\n\npublic class Twice {\n  public int of(int n) {\n    return n * 2;\n  }\n}\n"
              .getBytes(StandardCharsets.UTF_8));
      Files.write(
          root.resolve("src/test/java/fx/TwiceTest.java"),
          ("package fx;\n\nimport static org.junit.jupiter.api.Assertions.assertEquals;\n\n"
                  + "import org.junit.jupiter.api.Test;\n\nclass TwiceTest {\n  @Test\n"
                  + "  void doubles() {\n    assertEquals(6, new Twice().of(3));\n  }\n}\n")
              .getBytes(StandardCharsets.UTF_8));
      Map<?, ?> reloaded =
          kalku.ask(reload("src/main/java/fx/Twice.java", "src/test/java/fx/TwiceTest.java"));
      assertEquals("reloaded", reloaded.get("type"), reloaded.toString());

      Map<?, ?> after = kalku.ask("{\"type\":\"baseline\",\"id\":6}");
      assertEquals("green", after.get("status"), after.toString());
      assertTrue(testsOf(after).contains("fx.TwiceTest#doubles()"), testsOf(after).toString());
    }
  }

  @Test
  void aFileOutsideTheProjectIsRefused() throws Exception {
    Path root = project();
    try (Talk.Conversation kalku = new Talk.Conversation()) {
      prepared(kalku, root, this);
      Map<?, ?> said = kalku.ask(reload("../elsewhere/A.java"));
      assertEquals("bad_request", said.get("code"), said.toString());
    }
  }
}
