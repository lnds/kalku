package fx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GateTest {
  @Test
  void opensAtTenAndNotAtNine() {
    assertTrue(new Gate().opens(10));
    assertFalse(new Gate().opens(9));
  }

  @Test
  void zeroIsLowAndOneIsNot() {
    assertTrue(new Gate().low(0));
    assertFalse(new Gate().low(1));
  }
}
