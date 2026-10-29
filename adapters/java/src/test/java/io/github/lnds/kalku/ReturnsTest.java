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
 * A {@code return} wekufe is only a measurement when it compiles: a neutral value the type does
 * not take is a compile error, which costs a build and tells nothing about the suite.
 */
class ReturnsTest {
  private static final Path FIXTURE = GoldenTest.FIXTURES.resolve("return").resolve("Values.java");

  @Test
  void everyReturnWekufeOfTheFixtureCompiles(@TempDir Path out) throws Exception {
    byte[] data = Files.readAllBytes(FIXTURE);
    String text = new String(data, StandardCharsets.UTF_8);
    List<Sites.Site> sites =
        Sites.find(
            "Values.java", text, Collections.singleton(Spell.RETURN), Collections.emptyList());
    assertTrue(sites.size() >= 15, "only " + sites.size() + " return sites");
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
