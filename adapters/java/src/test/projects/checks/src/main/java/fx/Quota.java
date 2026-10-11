package fx;

public final class Quota {
  private Quota() {}

  public static final int CAP = 10;

  public static boolean spent(int used) {
    return used >= 3;
  }
}
