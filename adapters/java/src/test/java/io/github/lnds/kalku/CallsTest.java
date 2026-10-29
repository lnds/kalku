package io.github.lnds.kalku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code call} wekufe is only a measurement when it compiles: a call left out of a {@code try}
 * whose {@code catch} names what only that call throws is a compile error.
 */
class CallsTest {
  private static final Path FIXTURE =
      GoldenTest.FIXTURES.resolve("call").resolve("Statements.java");

  @Test
  void everyCallWekufeOfTheFixtureCompiles(@TempDir Path out) throws Exception {
    byte[] data = Files.readAllBytes(FIXTURE);
    String text = new String(data, StandardCharsets.UTF_8);
    List<Sites.Site> sites =
        Sites.find(
            "Statements.java", text, Collections.singleton(Spell.CALL), Collections.emptyList());
    assertTrue(sites.size() >= 10, "only " + sites.size() + " call sites");
    assertEquals("", Javac.errors(text, out), "the fixture itself");
    for (Sites.Site site : sites) {
      String wekufe =
          new String(Arrays.copyOfRange(data, 0, site.start.at), StandardCharsets.UTF_8)
              + site.replacement
              + new String(
                  Arrays.copyOfRange(data, site.end.at, data.length), StandardCharsets.UTF_8);
      assertEquals("", Javac.errors(wekufe, out), site.original + " -> " + site.replacement);
    }
  }
}
