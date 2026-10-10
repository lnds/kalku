package fx;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

// Named the way a test is, and left out by the build: it never passes.
class BrokenTest {
  @Test
  void isLeftOutByTheBuild() {
    fail("the build excludes this class");
  }
}
