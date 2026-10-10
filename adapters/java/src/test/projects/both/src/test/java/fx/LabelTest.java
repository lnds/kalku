package fx;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.Test;

// Written for TestNG, and the only test that looks at a label.
public class LabelTest {
  @Test
  public void zeroHasItsOwnLabel() {
    assertEquals(new Calc().label(0), "zero");
  }
}
