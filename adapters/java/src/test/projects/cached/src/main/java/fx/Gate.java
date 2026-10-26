package fx;

/** A gate that opens at ten. */
public final class Gate {
  private Gate() {}

  public static boolean open(int n) {
    return n >= 10;
  }

  public static String name() {
    return "gate";
  }
}
