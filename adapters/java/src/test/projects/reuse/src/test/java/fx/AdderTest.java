package fx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AdderTest {
  @Test
  void twoAndTwo() {
    assertEquals(4, Adder.add(2, 2));
  }

  @Test
  void nothingAddsNothing() {
    assertEquals(3, Adder.add(3, 0));
  }
}
