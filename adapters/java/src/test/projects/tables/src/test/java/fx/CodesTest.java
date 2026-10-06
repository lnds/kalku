package fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

// In this order on purpose: the first test to use `Codes` is the one that makes it fill its
// table, and it is not the one that looks at what the table holds.
@TestMethodOrder(MethodOrderer.MethodName.class)
class CodesTest {
  private static String before;

  // What runs before any test runs in none of them.
  @BeforeAll
  static void labelled() {
    before = Codes.label(3);
  }

  @Test
  void aKnowsItsNames() {
    assertTrue(Codes.known("a"));
  }

  @Test
  void bReadsWhatTheTableHolds() {
    assertEquals(1, Codes.of("a"));
    assertEquals(2, Codes.of("b"));
  }

  @Test
  void cWasLabelledBeforeAnyTest() {
    assertEquals("many", before);
  }
}
