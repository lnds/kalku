package fx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// Runs the same classes as `AdderTest`, and is a class of its own all the same.
class SumTest {
  @Test
  void oneAndOne() {
    assertEquals(2, Adder.add(1, 1));
  }
}
