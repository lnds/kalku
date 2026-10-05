package io.github.lnds.kalku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * This kalku against the protocol's own fixtures: the same lines the kaikai side and every
 * other kalku are held to.
 */
class ProtocolFixturesTest {
  private static final Path FIXTURES = Paths.get("..", "..", "docs", "protocol", "fixtures");

  private static List<String> lines(Path path) throws IOException {
    return Files.readAllLines(path, StandardCharsets.UTF_8);
  }

  private static List<Path> files(String... under) throws IOException {
    try (Stream<Path> all = Files.list(Paths.get(FIXTURES.toString(), under))) {
      return all.sorted().collect(Collectors.toList());
    }
  }

  @Test
  void everyRequestFixtureDecodes() throws Exception {
    int seen = 0;
    for (Path path : files("kalku", "requests")) {
      for (String line : lines(path)) {
        seen++;
        assertNotNull(Protocol.decode(line), line);
      }
    }
    assertTrue(seen >= 10);
  }

  @Test
  void everyInvalidRequestIsRefusedWithItsKind() throws Exception {
    for (String entry : lines(FIXTURES.resolve("invalid").resolve("kalku_requests.ndjson"))) {
      Map<?, ?> v = (Map<?, ?>) Json.decode(entry);
      String line = (String) v.get("line");
      Protocol.DecodeError refused =
          assertThrows(Protocol.DecodeError.class, () -> Protocol.decode(line), line);
      assertEquals(v.get("expect"), refused.kind, line);
    }
  }

  @Test
  void framingMatchesItsFixtures() throws Exception {
    for (String entry : lines(FIXTURES.resolve("invalid").resolve("framing.ndjson"))) {
      Map<?, ?> v = (Map<?, ?>) Json.decode(entry);
      int max = ((Long) v.get("max")).intValue();
      ByteArrayInputStream in =
          new ByteArrayInputStream(((String) v.get("input")).getBytes(StandardCharsets.UTF_8));
      List<String> got = new ArrayList<>();
      String line;
      while ((line = Framing.readLine(in, max)) != null) {
        got.add(line);
      }
      boolean tooLong = got.stream().anyMatch(g -> g == Framing.TOO_LONG);
      assertEquals(v.get("expect").equals("line_too_long"), tooLong, entry);
      // The last line is always readable, whatever came before it.
      assertTrue(got.get(got.size() - 1).contains("\"id\":8"), entry);
    }
  }

  // Valid fixtures are canonical, so decoding one and encoding it again gives the same bytes.
  // The codec is held to every message of the protocol that way, not only to its own.
  @Test
  void everyFixtureWithoutARealSurvivesTheCodecByteForByte() throws Exception {
    int seen = 0;
    List<Path> all = new ArrayList<>();
    all.addAll(files("kalku", "requests"));
    all.addAll(files("kalku", "replies"));
    all.addAll(files("shapes"));
    for (Path path : all) {
      for (String line : lines(path)) {
        if (hasReal(Json.decode(line))) {
          continue;
        }
        seen++;
        assertEquals(line, Json.encode(Json.decode(line)), path.toString());
      }
    }
    assertTrue(seen >= 30, "only " + seen + " fixture lines were read");
  }

  private static boolean hasReal(Object value) {
    if (value instanceof Double) {
      return true;
    }
    if (value instanceof Map) {
      return ((Map<?, ?>) value).values().stream().anyMatch(ProtocolFixturesTest::hasReal);
    }
    if (value instanceof List) {
      return ((List<?>) value).stream().anyMatch(ProtocolFixturesTest::hasReal);
    }
    return false;
  }

  @Test
  void whatItSaysIsTheFixturesByteForByte() throws Exception {
    Path replies = FIXTURES.resolve("kalku").resolve("replies");
    List<String> errors = lines(replies.resolve("error.ndjson"));
    assertTrue(
        errors.contains(
            Protocol.error(
                1, "protocol_mismatch", "kalku speaks protocol 2, kaikai side speaks 1", true)));
    assertTrue(
        errors.contains(
            Protocol.error(5, "unknown_test", "no test test/my_app/parser_test.exs:99", false)));
    assertTrue(lines(replies.resolve("bye.ndjson")).contains(Protocol.bye(7)));
    assertTrue(
        lines(replies.resolve("sites_found.ndjson"))
            .contains(
                Protocol.sitesFound(4, Collections.emptyList(), Collections.emptyList())));
  }

  @Test
  void aReadyReplyIsInCanonicalOrder() throws Exception {
    String said = Protocol.ready(1, "0.7.0", "Java 11", Collections.singletonList("cast"));
    Map<?, ?> v = (Map<?, ?>) Json.decode(said);
    assertEquals(
        Arrays.asList(
            "type", "id", "protocol", "language", "adapter", "runtime", "spells", "capabilities"),
        new ArrayList<>(v.keySet()));
    assertEquals("java", v.get("language"));
    assertEquals(Spell.CAST, v.get("spells"));
  }

  @Test
  void aLineTooLongIsOneValueAndNoOtherStringIsIt() {
    assertSame(Framing.TOO_LONG, Framing.TOO_LONG);
    assertTrue(!"line_too_long".equals(null) && "line_too_long" != Framing.TOO_LONG);
  }
}
