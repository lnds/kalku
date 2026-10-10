package fx;

public class Gate {
  static final int FLOOR = 1;

  // The bound lives in the other directory, as a constant the compiler copies in here.
  public boolean opens(int n) {
    return n >= Limits.CAP;
  }

  public boolean low(int n) {
    return n < FLOOR;
  }
}
