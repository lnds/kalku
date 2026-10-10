package fx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// Fails the first time it runs in a JVM and passes after that.
class FlakyTest {
  private static int runs;

  @Test
  void passesTheSecondTime() {
    runs++;
    assertTrue(runs > 1, "run " + runs);
  }
}
