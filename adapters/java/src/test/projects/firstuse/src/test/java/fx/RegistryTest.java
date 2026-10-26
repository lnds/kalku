package fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

// The first test makes `Registry` count the shelf; only the second looks at the count.
@TestMethodOrder(MethodOrderer.MethodName.class)
class RegistryTest {
  @Test
  void aIsThere() {
    assertTrue(Registry.size() >= 0);
  }

  @Test
  void bCountsTheShelf() {
    assertEquals(3, Registry.size());
  }
}
