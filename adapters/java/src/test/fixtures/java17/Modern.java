package fx.modern;

class Modern {
  // A switch expression has to be exhaustive: a case goes only while the default stays.
  int score(int n) {
    return switch (n) {
      case 1, 2 -> 10;
      case 3 -> {
        yield 30;
      }
      default -> 0;
    };
  }

  // Exhaustive over an enum, no default: nothing is proposed.
  int sides(Shape s) {
    return switch (s) {
      case SQUARE -> 4;
      case TRIANGLE -> 3;
    };
  }

  void arrows(int n) {
    switch (n) {
      case 1 -> tell("one");
      case 2 -> {
        tell("two");
      }
      default -> {}
    }
  }

  // A pattern binds a name: the condition around it is neither negated nor reconnected.
  int bound(Object o) {
    if (o instanceof String s && s.length() > 2) {
      return s.length();
    }
    if (!(o instanceof Integer i)) {
      return 0;
    }
    return i + 1;
  }

  String block() {
    return """
        several
        lines
        """;
  }

  void tell(String s) {}

  enum Shape {
    SQUARE,
    TRIANGLE
  }

  record Point(int x, int y) {
    Point {
      if (x < 0) {
        throw new IllegalArgumentException("x");
      }
    }

    int sum() {
      return x + y;
    }
  }
}
