package fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QuotaTest {
  @Test
  void theCapIsTen() {
    assertEquals(10, Quota.CAP);
  }

  @Test
  void spentAtThreeAndNotAtTwo() {
    assertTrue(Quota.spent(3));
    assertFalse(Quota.spent(2));
  }
}
