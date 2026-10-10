package fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CalcTest {
  @Test
  void tenIsBigAndNineIsNot() {
    assertTrue(new Calc().big(10));
    assertFalse(new Calc().big(9));
  }

  // The build sets this variable for its tests; nothing else does.
  @Test
  void theBuildsEnvironmentReachesTheTests() {
    assertEquals("south", System.getenv("FX_REGION"));
  }
}
