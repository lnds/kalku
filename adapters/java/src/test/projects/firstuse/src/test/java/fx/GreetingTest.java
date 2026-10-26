package fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

// In this order on purpose: `Greeting` is first used by the first test, after this class has
// said how to greet, and the test that looks at the whole word is the second.
@TestMethodOrder(MethodOrderer.MethodName.class)
class GreetingTest {
  @BeforeAll
  static void formally() {
    System.setProperty("fx.formal", "true");
  }

  @Test
  void aGreets() {
    assertTrue(Greeting.word().startsWith("good"));
  }

  @Test
  void bGreetsInFull() {
    assertEquals("good day", Greeting.word());
  }
}
