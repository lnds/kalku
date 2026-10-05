package fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CalcTest {
  private final Calc calc = new Calc();

  @ParameterizedTest
  @CsvSource({"1, 2, 3", "0, 0, 0", "-4, 4, 0"})
  void adds(int a, int b, int sum) {
    assertEquals(sum, calc.add(a, b));
  }

  @Test
  void tenIsBigAndNineIsNot() {
    assertTrue(calc.big(10));
    assertFalse(calc.big(9));
  }

  // The test names the constant: whatever the limit is, that is what a clamp gives.
  @Test
  void clampsToTheLimit() {
    assertEquals(Calc.LIMIT, calc.clamp(99));
    assertEquals(2, calc.clamp(2));
  }

  @Test
  void zeroHasItsOwnLabel() {
    assertEquals("zero", calc.label(0));
  }

  @Nested
  class Settings {
    @Test
    void theBuildsOwnSettingsReachTheTests() {
      assertEquals("strict/yes", calc.mode());
    }
  }
}
