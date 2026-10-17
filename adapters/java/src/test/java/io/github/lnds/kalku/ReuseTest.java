package io.github.lnds.kalku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A baseline that runs only what changed, on `src/test/projects/reuse`: `Adder` is run by
 * `AdderTest` and `SumTest`, `Greeter` by `GreeterTest` alone, `Words` holds a constant
 * `Greeter` is compiled with, and `Named` is an interface with no code.
 */
class ReuseTest {
  private static final String ADDER = "fx.AdderTest";
  private static final String GREETER = "fx.GreeterTest";
  private static final String SUM = "fx.SumTest";
  private static final List<String> EVERY =
      Arrays.asList(
          "fx.AdderTest#nothingAddsNothing()",
          "fx.AdderTest#twoAndTwo()",
          "fx.GreeterTest#greetsByName()",
          "fx.SumTest#oneAndOne()");

  @TempDir Path temp;

  // A project and the baselines measured in it, with what the kaikai side would keep.
  private final class Measured {
    final Path root;
    final Path reni;
    final Map<String, String> kept = new LinkedHashMap<>();

    Measured(String name) throws Exception {
      root = temp.resolve(name);
      reni = temp.resolve("reni-" + name);
      CastTest.copy(Paths.get("src", "test", "projects", "reuse"), root);
    }

    // The same sources, with a Gradle build in place of the `pom.xml`.
    Measured gradle() throws Exception {
      Files.delete(root.resolve("pom.xml"));
      Path build = Paths.get("src", "test", "projects", "gradle", "reuse");
      for (String file : Arrays.asList("build.gradle", "settings.gradle")) {
        Files.copy(build.resolve(file), root.resolve(file));
      }
      return this;
    }

    // A baseline offered every fingerprint kept so far.
    Map<?, ?> baseline() throws Exception {
      return baseline(kept);
    }

    Map<?, ?> baseline(Map<String, String> offer) throws Exception {
      Map<?, ?> done = measure(offer);
      assertEquals("green", done.get("status"), done.toString());
      return done;
    }

    Map<?, ?> measure(Map<String, String> offer) throws Exception {
      List<Map<String, Object>> reuse = new ArrayList<>();
      for (Map.Entry<String, String> each : offer.entrySet()) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("test", each.getKey());
        one.put("fingerprint", each.getValue());
        reuse.add(one);
      }
      Map<String, Object> asked = new LinkedHashMap<>();
      asked.put("type", "baseline");
      asked.put("id", 3L);
      if (!reuse.isEmpty()) {
        asked.put("reuse", reuse);
      }
      List<Map<?, ?>> said = Talk.ask(hello(), "{\"type\":\"prepare\",\"id\":2}", Json.encode(asked));
      assertEquals("prepared", said.get(1).get("type"), said.get(1).toString());
      Map<?, ?> done = said.get(2);
      assertEquals("baseline_done", done.get("type"), done.toString());
      for (Object each : (List<?>) done.get("tests")) {
        Map<?, ?> test = (Map<?, ?>) each;
        Object print = test.get("fingerprint");
        if (print == null) {
          kept.remove((String) test.get("test"));
        } else {
          kept.put((String) test.get("test"), (String) print);
        }
      }
      return done;
    }

    void write(String file, String text) throws Exception {
      Path at = root.resolve(file);
      Files.createDirectories(at.getParent());
      Files.write(at, text.getBytes(StandardCharsets.UTF_8));
    }

    void replace(String file, String from, String to) throws Exception {
      String text = new String(Files.readAllBytes(root.resolve(file)), StandardCharsets.UTF_8);
      assertTrue(text.contains(from), file);
      write(file, text.replace(from, to));
    }

    private String hello() {
      Map<String, Object> o = new LinkedHashMap<>();
      o.put("type", "hello");
      o.put("id", 1L);
      o.put("protocol", 1L);
      o.put("root", root.toString());
      o.put("reni", reni.toString());
      o.put("worker", 0L);
      o.put("inline_limit_bytes", 65536L);
      o.put("env", new LinkedHashMap<String, Object>());
      return Json.encode(o);
    }
  }

  private static TreeSet<String> ran(Map<?, ?> done) {
    TreeSet<String> out = new TreeSet<>();
    for (Object each : (List<?>) done.get("tests")) {
      out.add((String) ((Map<?, ?>) each).get("test"));
    }
    return out;
  }

  private static TreeSet<String> reused(Map<?, ?> done) {
    Object said = done.get("reused");
    TreeSet<String> out = new TreeSet<>();
    if (said != null) {
      for (Object each : (List<?>) said) {
        out.add((String) each);
      }
    }
    return out;
  }

  private static TreeSet<String> of(String... classes) {
    TreeSet<String> out = new TreeSet<>();
    for (String test : EVERY) {
      for (String c : classes) {
        if (test.startsWith(c + "#")) {
          out.add(test);
        }
      }
    }
    return out;
  }

  @Test
  void theTestsOfOneClassShareAFingerprintAndTheTestsOfAnotherDoNot() throws Exception {
    Measured m = new Measured("prints");
    Map<?, ?> first = m.baseline();
    assertEquals(new TreeSet<>(EVERY), ran(first));
    assertEquals(new TreeSet<>(EVERY), new TreeSet<>(m.kept.keySet()), first.toString());
    assertEquals(m.kept.get(EVERY.get(0)), m.kept.get(EVERY.get(1)));
    assertNotEquals(m.kept.get(EVERY.get(0)), m.kept.get(EVERY.get(2)));
    // Two classes whose tests ran the same classes are told apart.
    assertNotEquals(m.kept.get(EVERY.get(0)), m.kept.get(EVERY.get(3)));
  }

  @Test
  void aBaselineWithNothingChangedRunsNothing() throws Exception {
    Measured m = new Measured("unchanged");
    m.baseline();
    Map<?, ?> again = m.baseline();
    assertEquals(Collections.emptySet(), ran(again), again.toString());
    assertEquals(new TreeSet<>(EVERY), reused(again));
    assertEquals(Collections.emptyList(), again.get("coverage"));
    assertEquals(Collections.emptyList(), again.get("failures"));
  }

  @Test
  void aChangedClassRunsAgainOnlyTheTestsThatRanIt() throws Exception {
    Measured m = new Measured("changed");
    m.baseline();
    m.replace("src/main/java/fx/Adder.java", "return a + b;", "int sum = a + b;\n    return sum;");
    Map<?, ?> adder = m.baseline();
    assertEquals(of(ADDER, SUM), ran(adder), adder.toString());
    assertEquals(of(GREETER), reused(adder));
    // What ran is credited with what it reached, and what was reused is the kaikai side's.
    assertNotNull(adder.get("coverage"), adder.toString());

    // A constant is in the bytecode of the class that uses it, not in its own.
    m.replace("src/main/java/fx/Words.java", "\"hello\"", "\"hi\"");
    Map<?, ?> words = m.baseline();
    assertEquals(of(GREETER), ran(words), words.toString());
    assertEquals(of(ADDER, SUM), reused(words));
  }

  @Test
  void aClassWithNoCodeIsInEveryFingerprint() throws Exception {
    Measured m = new Measured("codeless");
    m.baseline();
    m.replace("src/main/java/fx/Named.java", "String greet(String name);", "String greet(String name);\n\n  String NOBODY = \"\";");
    Map<?, ?> again = m.baseline();
    assertEquals(new TreeSet<>(EVERY), ran(again), again.toString());
    assertEquals(Collections.emptySet(), reused(again));
  }

  @Test
  void aFileTheTestsCouldReadRunsEveryTestAgain() throws Exception {
    Measured m = new Measured("data");
    m.baseline();
    m.write("src/test/resources/fx/note.txt", "read me\n");
    Map<?, ?> again = m.baseline();
    assertEquals(new TreeSet<>(EVERY), ran(again), again.toString());
  }

  @Test
  void aClassIsLeftOutOnlyWhenEveryTestItRanIsOffered() throws Exception {
    Measured m = new Measured("partly");
    m.baseline();
    Map<String, String> offer = new LinkedHashMap<>(m.kept);
    offer.remove(EVERY.get(0));
    Map<?, ?> again = m.baseline(offer);
    assertEquals(of(ADDER), ran(again), again.toString());
    assertEquals(of(GREETER, SUM), reused(again));
  }

  @Test
  void aFingerprintThisKalkuDidNotKeepIsNotTrusted() throws Exception {
    Measured m = new Measured("forgotten");
    m.baseline();
    Path prints = m.reni.resolve("prints");
    try (java.util.stream.Stream<Path> all = Files.list(prints)) {
      for (Path p : (Iterable<Path>) all::iterator) {
        Files.delete(p);
      }
    }
    Map<?, ?> again = m.baseline();
    assertEquals(new TreeSet<>(EVERY), ran(again), again.toString());
    // And one this kalku would never write is no fingerprint at all.
    Map<String, String> made = new LinkedHashMap<>();
    for (String test : EVERY) {
      made.put(test, "../../etc/passwd");
    }
    assertEquals(new TreeSet<>(EVERY), ran(m.baseline(made)));
  }

  @Test
  void aTestThatFailsWhereOthersWereLeftOutIsToldByTheWholeSuite() throws Exception {
    Measured m = new Measured("red");
    m.baseline();
    m.replace("src/main/java/fx/Adder.java", "return a + b;", "return a - b;");
    Map<?, ?> again = m.measure(m.kept);
    assertEquals("red", again.get("status"), again.toString());
    assertEquals(new TreeSet<>(EVERY), ran(again));
    assertEquals(Collections.emptySet(), reused(again));
    for (Object each : (List<?>) again.get("tests")) {
      assertEquals(null, ((Map<?, ?>) each).get("fingerprint"), each.toString());
    }
  }

  @Test
  void aGradleBuildRunsOnlyWhatChangedToo() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(CastTest.gradleRuns(temp), "no Gradle that runs on this JDK");
    Measured m = new Measured("gradle").gradle();
    m.baseline();
    assertEquals(new TreeSet<>(EVERY), new TreeSet<>(m.kept.keySet()));
    m.replace("src/main/java/fx/Adder.java", "return a + b;", "int sum = a + b;\n    return sum;");
    Map<?, ?> again = m.baseline();
    assertEquals(of(ADDER, SUM), ran(again), again.toString());
    assertEquals(of(GREETER), reused(again));
  }
}
