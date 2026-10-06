package fx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CalcTest {
  private final Calc calc = new Calc();

  @Test
  public void tenIsBigAndNineIsNot() {
    assertTrue(calc.big(10));
    assertFalse(calc.big(9));
  }

  // The test names the constant: whatever the limit is, that is what a clamp gives.
  @Test
  public void clampsToTheLimit() {
    assertEquals(Calc.LIMIT, calc.clamp(99));
    assertEquals(2, calc.clamp(2));
  }

  @Test
  public void zeroHasItsOwnLabel() {
    assertEquals("zero", calc.label(0));
  }

  @Test
  public void theBuildsOwnSettingsReachTheTests() {
    assertEquals("strict/yes", calc.mode());
  }
}
