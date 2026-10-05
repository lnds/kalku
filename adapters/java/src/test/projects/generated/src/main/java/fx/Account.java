package fx;

import lombok.Getter;

@Getter
public class Account {
  private final int balance;

  public Account(int balance) {
    this.balance = balance;
  }

  public boolean rich() {
    return getBalance() >= 100;
  }
}
