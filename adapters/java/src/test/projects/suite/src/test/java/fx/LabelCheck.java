package fx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// Not a name a build takes for a test unless it says so, and this one does.
class LabelCheck {
  @Test
  void aNumberThatIsNotZeroIsSome() {
    assertEquals("some", new Calc().label(3));
  }
}
