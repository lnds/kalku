package fx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AccountTest {
  @Test
  void aHundredIsRich() {
    assertTrue(new Account(100).rich());
  }
}
