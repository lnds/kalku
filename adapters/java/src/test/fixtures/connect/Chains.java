package fx.connect;

class Chains {
  boolean both(boolean a, boolean b) {
    return a && b;
  }

  boolean either(boolean a, boolean b) {
    return a || b;
  }

  boolean mixed(boolean a, boolean b, boolean c) {
    return a && b || c;
  }

  // Bitwise operators are not connectives.
  int bits(int a, int b) {
    return a & b | a ^ b;
  }

  boolean spread(boolean a, boolean b) {
    return a
        && /* not || */ b;
  }
}
