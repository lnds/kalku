package fx.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PricesTest {
  @Test
  void aPriceIsMoreThanNothing() {
    assertTrue(Prices.valid(1));
    assertFalse(Prices.valid(0));
  }
}
