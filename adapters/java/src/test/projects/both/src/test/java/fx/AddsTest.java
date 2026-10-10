package fx;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

// Written for JUnit 4.
public class AddsTest {
  @Test
  public void adds() {
    assertEquals(3, new Calc().add(1, 2));
  }
}
