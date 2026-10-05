package fx.patterns;

class Patterns {
  // A type pattern is a label; its guard is code.
  int measure(Object o) {
    return switch (o) {
      case String s when s.length() > 3 -> 1;
      case String s -> 2;
      case Integer i -> i + 3;
      case null, default -> 0;
    };
  }

  int plain(Object o) {
    switch (o) {
      case Integer i when i >= 10:
        return 10;
      case Integer i:
        return i;
      default:
        return -1;
    }
  }

  record Pair(Object left, Object right) {}

  boolean both(Object o) {
    return o instanceof Pair(String a, String b) && a.equals(b);
  }
}
