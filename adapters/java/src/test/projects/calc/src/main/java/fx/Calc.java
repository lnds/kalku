package fx;

public class Calc {
  public static final int LIMIT = 5;

  public int add(int a, int b) {
    return a + b;
  }

  // The bound lives in another class, as a constant the compiler copies in here.
  public boolean big(int n) {
    return n >= Limits.CAP;
  }

  public int clamp(int n) {
    if (n > LIMIT) {
      return LIMIT;
    }
    return n;
  }

  // No test looks at what this returns for a number that is not zero.
  public String label(int n) {
    return n == 0 ? "zero" : "some";
  }

  public String mode() {
    return System.getProperty("fx.mode", "lax") + "/" + System.getProperty("fx.from.argline", "no");
  }
}
