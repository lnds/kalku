package fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

// The gate is kept where every class loader of a JVM reaches it, as a framework may keep what
// it built: the first test of a JVM to ask for it keeps the one it found, and every test after
// it, in that JVM, gets that one.
class GateTest {
  private static IntPredicate gate() {
    return (IntPredicate)
        System.getProperties().computeIfAbsent("fx.gate", k -> (IntPredicate) Gate::open);
  }

  @Test
  void opensAtTen() {
    assertTrue(gate().test(10));
    assertFalse(gate().test(9));
  }

  @Test
  void isNamed() {
    assertEquals("gate", Gate.name());
  }
}
