package fx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DoorTest {
  @Test
  void eighteenIsWelcome() {
    Greeter door = new Door();
    assertTrue(door.welcomes(18));
    assertFalse(door.welcomes(17));
  }
}
