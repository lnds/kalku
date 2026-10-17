package fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GreeterTest {
  @Test
  void greetsByName() {
    Named greeter = new Greeter();
    assertEquals(Words.HELLO + ", ana", greeter.greet("ana"));
    assertTrue(greeter instanceof Named);
  }
}
