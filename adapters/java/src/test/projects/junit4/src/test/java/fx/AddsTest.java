package fx;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

// JUnit 4's way of running one test with several sets of values: each is a test of its own
// to the runner, named `adds[0]`, `adds[1]`…
@RunWith(Parameterized.class)
public class AddsTest {
  @Parameters
  public static Collection<Object[]> sums() {
    return Arrays.asList(new Object[][] {{1, 2, 3}, {0, 0, 0}, {-4, 4, 0}});
  }

  private final int a;
  private final int b;
  private final int sum;

  public AddsTest(int a, int b, int sum) {
    this.a = a;
    this.b = b;
    this.sum = sum;
  }

  @Test
  public void adds() {
    assertEquals(sum, new Calc().add(a, b));
  }
}
