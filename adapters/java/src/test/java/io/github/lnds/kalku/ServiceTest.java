package io.github.lnds.kalku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceTest {
  @TempDir Path root;

  @BeforeEach
  void aProject() throws Exception {
    write("src/main/java/a/Ok.java", "package a;\nclass Ok { boolean f(int x) { return x >= 1; } }\n");
    write("src/main/java/a/Broken.java", "package a;\nclass Broken { int f( { return 1 } }\n");
    write("src/test/java/a/OkTest.java", "package a;\nclass OkTest { boolean t() { return true; } }\n");
    write("src/main/java/a/CheckTest.java", "package a;\nclass CheckTest { boolean t() { return true; } }\n");
    write("README.md", "# not Java\n");
    Path latin = root.resolve("src/main/java/a/Latin.java");
    Files.write(latin, new byte[] {'/', '/', (byte) 0xF1, '\n'});
  }

  private void write(String file, String text) throws Exception {
    Path path = root.resolve(file);
    Files.createDirectories(path.getParent());
    Files.write(path, text.getBytes(StandardCharsets.UTF_8));
  }

  // What the service says, line by line, to these requests.
  private List<Map<?, ?>> ask(String... requests) throws Exception {
    byte[] in = (String.join("\n", requests) + "\n").getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    new Service(new ByteArrayInputStream(in), out, "test").serve();
    List<Map<?, ?>> said = new ArrayList<>();
    for (String line : new String(out.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
      said.add((Map<?, ?>) Json.decode(line));
    }
    return said;
  }

  private String hello(long protocol) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("type", "hello");
    o.put("id", 1L);
    o.put("protocol", protocol);
    o.put("root", root.toString());
    o.put("reni", root.resolve(".reni").toString());
    o.put("worker", 0L);
    o.put("inline_limit_bytes", 65536L);
    o.put("env", new LinkedHashMap<String, Object>());
    return Json.encode(o);
  }

  private static String sites(String... files) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("type", "sites");
    o.put("id", 2L);
    o.put("files", Arrays.asList(files));
    o.put("spells", Spell.CAST);
    o.put("exclude_calls", new ArrayList<String>());
    return Json.encode(o);
  }

  @Test
  void itAnnouncesWhatItIsAndOnlyWhatItCanDo() throws Exception {
    Map<?, ?> ready = ask(hello(1)).get(0);
    assertEquals("ready", ready.get("type"));
    assertEquals("java", ready.get("language"));
    assertEquals("test", ready.get("adapter"));
    assertTrue(((String) ready.get("runtime")).startsWith("Java "));
    assertEquals(Arrays.asList("cast"), ready.get("capabilities"));
  }

  @Test
  void anotherProtocolVersionIsRefusedForGood() throws Exception {
    Map<?, ?> said = ask(hello(2)).get(0);
    assertEquals("protocol_mismatch", said.get("code"));
    assertEquals(true, said.get("fatal"));
  }

  @Test
  void nothingIsServedBeforeHello() throws Exception {
    Map<?, ?> said = ask(sites("src/main/java/a/Ok.java")).get(0);
    assertEquals("not_ready", said.get("code"));
  }

  @Test
  void aFileIsSearchedOrSkippedWithItsReason() throws Exception {
    Map<?, ?> found =
        ask(
                hello(1),
                sites(
                    "src/main/java/a/Ok.java",
                    "src/main/java/a/Broken.java",
                    "src/test/java/a/OkTest.java",
                    "src/main/java/a/CheckTest.java",
                    "src/main/java/a/Latin.java",
                    "src/main/java/a/Missing.java",
                    "README.md"))
            .get(1);
    assertEquals("sites_found", found.get("type"));

    List<?> sites = (List<?>) found.get("sites");
    assertFalse(sites.isEmpty());
    for (Object site : sites) {
      assertEquals("src/main/java/a/Ok.java", ((Map<?, ?>) site).get("file"));
    }

    Map<String, String> why = new LinkedHashMap<>();
    for (Object entry : (List<?>) found.get("skipped")) {
      Map<?, ?> skipped = (Map<?, ?>) entry;
      why.put((String) skipped.get("file"), (String) skipped.get("reason"));
      assertFalse(((String) skipped.get("message")).isEmpty());
    }
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("src/main/java/a/Broken.java", "parse_error");
    expected.put("src/test/java/a/OkTest.java", "test_file");
    expected.put("src/main/java/a/CheckTest.java", "test_file");
    expected.put("src/main/java/a/Latin.java", "unreadable");
    expected.put("src/main/java/a/Missing.java", "unreadable");
    expected.put("README.md", "not_java");
    assertEquals(expected, why);
  }

  @Test
  void aParseErrorCarriesTheCompilersOwnWords() throws Exception {
    Map<?, ?> found = ask(hello(1), sites("src/main/java/a/Broken.java")).get(1);
    Map<?, ?> skipped = (Map<?, ?>) ((List<?>) found.get("skipped")).get(0);
    assertTrue(((String) skipped.get("message")).startsWith("line 2: "), skipped.toString());
  }

  @Test
  void whatItCannotDoYetItSaysInsteadOfGoingQuiet() throws Exception {
    List<Map<?, ?>> said =
        ask(
            hello(1),
            "{\"type\":\"prepare\",\"id\":3}",
            "{\"type\":\"baseline\",\"id\":4}",
            "{\"type\":\"reset\",\"id\":5}");
    assertEquals(4, said.size());
    for (Map<?, ?> reply : said.subList(1, 4)) {
      assertEquals("error", reply.get("type"));
      assertEquals("not_implemented", reply.get("code"));
    }
  }

  @Test
  void aLineItCannotReadIsRefusedAndTheNextOneIsServed() throws Exception {
    List<Map<?, ?>> said = ask("not json", "{\"type\":\"frobnicate\",\"id\":9}", hello(1));
    assertEquals("bad_request", said.get(0).get("code"));
    assertTrue(((String) said.get(0).get("message")).startsWith("not_json"));
    assertEquals(9L, said.get(1).get("id"));
    assertTrue(((String) said.get(1).get("message")).startsWith("unknown_type"));
    assertEquals("ready", said.get(2).get("type"));
  }

  @Test
  void shutdownIsAnsweredAndNothingAfterItIsRead() throws Exception {
    List<Map<?, ?>> said = ask("{\"type\":\"shutdown\",\"id\":7}", hello(1));
    assertEquals(1, said.size());
    assertEquals("bye", said.get(0).get("type"));
  }

  @Test
  void theSuiteIsKnownByWhereItLivesAndHowItIsNamed() {
    assertTrue(Service.isTestFile("src/test/java/a/Thing.java"));
    assertTrue(Service.isTestFile("module/src/integrationTest/java/a/Thing.java"));
    assertTrue(Service.isTestFile("src/main/java/a/ThingTest.java"));
    assertTrue(Service.isTestFile("src/main/java/a/ThingIT.java"));
    assertFalse(Service.isTestFile("src/main/java/a/Thing.java"));
    assertFalse(Service.isTestFile("src/main/java/a/Testing.java"));
    assertFalse(Service.isTestFile("src/main/java/a/contest/Entry.java"));
  }
}
