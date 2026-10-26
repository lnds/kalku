package fx;

public final class Greeting {
  private Greeting() {}

  // Read when this class is first used, which is after the tests have said how to greet.
  private static final String WORD = Boolean.getBoolean("fx.formal") ? formal() : plain();

  private static String formal() {
    return "good day";
  }

  private static String plain() {
    return "hi";
  }

  public static String word() {
    return WORD;
  }
}
