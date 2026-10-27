package io.github.lnds.kalku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Casts whose tests run in a JVM that stays, and outcomes that are the ones a JVM of their own
 * gives.
 *
 * <p>The project is `src/test/projects/cached`. Its test keeps the gate it tests where every
 * class loader of a JVM reaches it, so in a JVM that stays a later cast's test is handed an
 * earlier cast's gate: what it tests is then not the wekufe, and a JVM that stays, believed,
 * would call a wekufe its test does notice a survivor, and one it does not notice a kill.
 */
class WarmTest {
  private static final Path PROJECTS = Paths.get("src", "test", "projects");
  private static final String GATE = "src/main/java/fx/Gate.java";
  private static final List<String> OPENS = Collections.singletonList("fx.GateTest#opensAtTen()");

  @TempDir static Path temp;
  static Path root;
  static Path reni;
  static final List<Map<?, ?>> sites = new ArrayList<>();

  @BeforeAll
  static void prepared() throws Exception {
    root = temp.resolve("cached");
    reni = temp.resolve("reni");
    CastTest.copy(PROJECTS.resolve("cached"), root);
    List<Map<?, ?>> said = Talk.ask(hello(root), "{\"type\":\"prepare\",\"id\":2}", sitesOf(GATE));
    assertEquals("prepared", said.get(1).get("type"), said.get(1).toString());
    for (Object site : (List<?>) said.get(2).get("sites")) {
      sites.add((Map<?, ?>) site);
    }
  }

  // The first cast leaves a gate that opens as the original does; the next one's test is
  // handed it, passes, and would leave the wekufe that changes `>=` for a survivor.
  @Test
  void aSurvivorOfAJvmThatStaysIsNotBelieved() throws Exception {
    assertEquals(
        Arrays.asList("survived", "killed", "killed"),
        outcomes(
            site("\"gate\"", "\"\""), OPENS,
            site(">=", ">"), OPENS,
            site("10", "11"), OPENS));
  }

  // The first cast leaves the wekufe's gate; the next one's test is handed it and fails, for
  // a wekufe it does not reach.
  @Test
  void aKillOfATestThatFailsOnTheOriginalTooIsNotBelieved() throws Exception {
    assertEquals(
        Arrays.asList("killed", "survived"),
        outcomes(site(">=", ">"), OPENS, site("\"gate\"", "\"\""), OPENS));
  }

  // What the JVM that stays saves: a kill whose test passes on the original is believed there,
  // and no other JVM is started for it.
  @Test
  void killsAreJudgedInTheJvmThatStays() throws Exception {
    List<String> named = Collections.singletonList("fx.GateTest#isNamed()");
    assertEquals(
        Arrays.asList("killed", "killed"),
        outcomes(site("\"gate\"", "\"\""), named, site("\"gate\"", "\"\""), named));
    // Each kill is two runs there: the cast's, and its test on the original.
    try (Stream<Path> asked = Files.list(warm())) {
      assertEquals(
          4, asked.filter(p -> p.getFileName().toString().startsWith("request-")).count());
    }
    assertTrue(Files.exists(warm().resolve("tests.log")));
  }

  private static Path warm() {
    return reni.resolve("cached").resolve("work").resolve("0").resolve("warm").resolve("0");
  }

  // One kalku casts these in turn: a site, then the tests it is cast against.
  private static List<String> outcomes(Object... casts) throws Exception {
    List<String> asked = new ArrayList<>();
    asked.add(hello(root));
    asked.add("{\"type\":\"prepare\",\"id\":2}");
    for (int i = 0; i < casts.length; i += 2) {
      Map<String, Object> o = new LinkedHashMap<>();
      Map<?, ?> site = (Map<?, ?>) casts[i];
      o.put("type", "cast");
      o.put("id", 10L + i);
      o.put("wekufe", site.get("site_id"));
      o.put("site", site);
      o.put("tests", casts[i + 1]);
      asked.add(Json.encode(o));
    }
    List<Map<?, ?>> said = Talk.ask(asked.toArray(new String[0]));
    assertEquals("prepared", said.get(1).get("type"), said.get(1).toString());
    return said.subList(2, said.size()).stream()
        .map(done -> String.valueOf(done.get("outcome")))
        .collect(Collectors.toList());
  }

  private static Map<?, ?> site(String original, String replacement) {
    List<Map<?, ?>> found =
        sites.stream()
            .filter(s -> original.equals(s.get("original")) && replacement.equals(s.get("replacement")))
            .collect(Collectors.toList());
    assertEquals(1, found.size(), "sites turning " + original + " into " + replacement);
    return found.get(0);
  }

  private static String hello(Path project) {
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

  private static String sitesOf(String file) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("type", "sites");
    o.put("id", 3L);
    o.put("files", Collections.singletonList(file));
    o.put("spells", Talk.DEFAULT_SPELLS);
    o.put("exclude_calls", new ArrayList<String>());
    return Json.encode(o);
  }
}
