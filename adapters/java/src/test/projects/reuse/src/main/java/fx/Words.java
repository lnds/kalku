package fx;

public final class Words {
  private Words() {}

  public static final String HELLO = "hello";

  // Code no test runs: what changes in `HELLO` shows only where it was copied.
  public static String shout(String word) {
    return word.toUpperCase();
  }
}
