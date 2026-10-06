package fx.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CheckoutTest {
  private final Checkout checkout = new Checkout();

  @Test
  void fiftyGetsTheDiscountAndFortyNineDoesNot() {
    assertEquals(45, checkout.total(50));
    assertEquals(49, checkout.total(49));
  }

  @Test
  void aHundredShipsFree() {
    assertTrue(checkout.shipsFree(100));
    assertFalse(checkout.shipsFree(99));
  }
}
