package fx;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class CalcTest {
  private final Calc calc = new Calc();

  @DataProvider
  public Object[][] sums() {
    return new Object[][] {{1, 2, 3}, {0, 0, 0}, {-4, 4, 0}};
  }

  // TestNG's way of running one test with several sets of values.
  @Test(dataProvider = "sums")
  public void adds(int a, int b, int sum) {
    assertEquals(calc.add(a, b), sum);
  }

  @Test
  public void tenIsBigAndNineIsNot() {
    assertTrue(calc.big(10));
    assertFalse(calc.big(9));
  }

  // The test names the constant: whatever the limit is, that is what a clamp gives.
  @Test
  public void clampsToTheLimit() {
    assertEquals(calc.clamp(99), Calc.LIMIT);
    assertEquals(calc.clamp(2), 2);
  }

  @Test
  public void zeroHasItsOwnLabel() {
    assertEquals(calc.label(0), "zero");
  }

  @Test
  public void theBuildsOwnSettingsReachTheTests() {
    assertEquals(calc.mode(), "strict/yes");
  }
}
