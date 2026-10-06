package fx.core;

public final class Prices {
  private Prices() {}

  public static final int FREE_FROM = 100;

  // Only the module that sells looks at what a discount comes to.
  public static int discounted(int price) {
    return price >= 50 ? price - 5 : price;
  }

  public static boolean valid(int price) {
    return price > 0;
  }
}
