package fx.app;

import fx.core.Prices;

public final class Checkout {
  public int total(int price) {
    return Prices.discounted(price);
  }

  public boolean shipsFree(int total) {
    return total >= Prices.FREE_FROM;
  }
}
