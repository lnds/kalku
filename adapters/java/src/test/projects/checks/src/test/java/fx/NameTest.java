package fx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NameTest {
  @Test
  void isCalledQuota() {
    assertEquals("Quota", Quota.class.getSimpleName());
  }
}
