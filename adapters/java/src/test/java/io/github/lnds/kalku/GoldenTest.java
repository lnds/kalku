package io.github.lnds.kalku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Each fixture is a source written a particular way on purpose, and beside it the exact sites
 * it must give. Set KALKU_UPDATE_GOLDENS=1 to rewrite the goldens after reviewing a change.
 *
 * <p>A fixture under {@code javaN} uses syntax that arrived in Java N and is only read from
 * there on. Every other fixture gives the same sites on every JDK.
 */
class GoldenTest {
  static final Path FIXTURES = Paths.get("src", "test", "fixtures");
  private static final List<String> EXCLUDE = Arrays.asList("log.*", "System.out.*");
  private static final boolean UPDATE = "1".equals(System.getenv("KALKU_UPDATE_GOLDENS"));
  private static final Pattern RELEASE = Pattern.compile("java(\\d+)");

  static List<Path> sources() throws IOException {
    try (Stream<Path> all = Files.walk(FIXTURES)) {
      return all.filter(p -> p.toString().endsWith(".java"))
          .filter(GoldenTest::readable)
          .sorted()
          .collect(Collectors.toList());
    }
  }

  private static boolean readable(Path source) {
    Matcher m = RELEASE.matcher(FIXTURES.relativize(source).getName(0).toString());
    return !m.matches() || Runtime.version().feature() >= Integer.parseInt(m.group(1));
  }

  static String relative(Path source) {
    Path named = source.startsWith(FIXTURES) ? FIXTURES.relativize(source) : source;
    return named.toString().replace('\\', '/');
  }

  // The kalku's own sources: code nobody wrote to be a fixture.
  private static List<Path> own() throws IOException {
    try (Stream<Path> all = Files.walk(Paths.get("src", "main", "java"))) {
      return all.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
    }
  }

  static List<Sites.Site> sites(Path source) throws Exception {
    String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    return Sites.find(relative(source), text, new HashSet<>(Spell.CAST), EXCLUDE);
  }

  @TestFactory
  List<DynamicTest> sitesMatchTheGolden() throws IOException {
    List<DynamicTest> tests = new ArrayList<>();
    for (Path source : sources()) {
      tests.add(
          DynamicTest.dynamicTest(
              relative(source),
              () -> {
                StringBuilder actual = new StringBuilder();
                for (Sites.Site site : sites(source)) {
                  actual.append(Json.encode(Protocol.site(site))).append('\n');
                }
                Path golden =
                    source.resolveSibling(
                        source.getFileName().toString().replace(".java", ".sites.ndjson"));
                if (UPDATE) {
                  Files.write(golden, actual.toString().getBytes(StandardCharsets.UTF_8));
                }
                String expected = new String(Files.readAllBytes(golden), StandardCharsets.UTF_8);
                assertEquals(expected, actual.toString());
              }));
    }
    assertTrue(tests.size() >= 9, "the fixtures are missing");
    return tests;
  }

  @Test
  void everySiteRoundTrips() throws Exception {
    int seen = 0;
    List<Path> searched = new ArrayList<>(sources());
    searched.addAll(own());
    for (Path source : searched) {
      byte[] data = Files.readAllBytes(source);
      String text = new String(data, StandardCharsets.UTF_8);
      for (Sites.Site site : sites(source)) {
        seen++;
        String where = relative(source) + " " + site.siteId;
        // The span, counted in bytes, holds exactly `original`.
        String held =
            new String(
                Arrays.copyOfRange(data, site.start.at, site.end.at), StandardCharsets.UTF_8);
        assertEquals(site.original, held, where);
        // The line and the column, counted in characters, point at the same place.
        String[] lines = text.split("\n", -1);
        String line = lines[site.start.line - 1];
        int at = line.offsetByCodePoints(0, site.start.col - 1);
        assertTrue(line.substring(at).startsWith(firstLine(site.original)), where);
        // What is cast differs from the original and is still a Java file.
        String wekufe =
            new String(Arrays.copyOfRange(data, 0, site.start.at), StandardCharsets.UTF_8)
                + site.replacement
                + new String(
                    Arrays.copyOfRange(data, site.end.at, data.length), StandardCharsets.UTF_8);
        assertTrue(!wekufe.equals(text), where);
        Sites.find("W.java", wekufe, Collections.emptySet(), Collections.emptyList());
      }
    }
    assertTrue(seen > 100, "only " + seen + " sites in the fixtures");
  }

  private static String firstLine(String text) {
    int end = text.indexOf('\n');
    return end < 0 ? text : text.substring(0, end);
  }

  @Test
  void aSiteIdDependsOnTheFileAndOnNothingElse() throws Exception {
    Path source = FIXTURES.resolve("compare").resolve("Comparisons.java");
    List<String> once = ids(sites(source));
    assertEquals(once, ids(sites(source)));
    assertEquals(once.size(), new HashSet<>(once).size(), "two sites share an id");
  }

  private static List<String> ids(List<Sites.Site> sites) {
    return sites.stream().map(s -> s.siteId).collect(Collectors.toList());
  }

  @Test
  void anOrdinalCountsWithinItsEnclosingDeclaration() throws Exception {
    Map<String, Long> most =
        sites(FIXTURES.resolve("connect").resolve("Chains.java")).stream()
            .collect(
                Collectors.groupingBy(
                    s -> s.enclosing + " " + s.original, Collectors.counting()));
    for (Sites.Site site : sites(FIXTURES.resolve("connect").resolve("Chains.java"))) {
      assertTrue(site.ordinal >= 1 && site.ordinal <= most.get(site.enclosing + " " + site.original));
    }
  }
}
