package fx.audit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import fx.core.Prices;
import org.junit.jupiter.api.Test;

class PricesAuditTest {
  @Test
  void fiveIsAPrice() {
    assertTrue(Prices.valid(5));
  }
}
