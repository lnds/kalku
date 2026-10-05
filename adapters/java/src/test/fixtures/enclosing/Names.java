package fx.enclosing;

import java.util.List;
import java.util.Map;

public class Names {
  static int counter = 1;

  static {
    counter = 2;
  }

  int field = 3;

  Names() {
    field = 4;
  }

  Names(int start) {
    field = start + 5;
  }

  // Overloads are told apart by what they take, as the source writes it.
  int size(String s) {
    return s.length() + 6;
  }

  int size(List<String> xs) {
    return xs.size() + 7;
  }

  int size(Map<String, List<Integer>> m, int[] ns, String... rest) {
    return m.size() + 8;
  }

  <T extends Comparable<T>> int generic(T a, java.util.Optional<T> b) {
    return 9;
  }

  Runnable anonymous() {
    return new Runnable() {
      @Override
      public void run() {
        counter = 10;
      }
    };
  }

  Runnable second() {
    Runnable inLambda = () -> counter = 11;
    return new Runnable() {
      public void run() {
        counter = 12;
      }
    };
  }

  int local() {
    class Helper {
      int help() {
        return 13;
      }
    }
    return new Helper().help();
  }

  static class Inner {
    int depth() {
      return 14;
    }

    class Deeper {
      int depth() {
        return 15;
      }
    }
  }

  interface Shape {
    default int sides() {
      return 16;
    }
  }

  enum Colour {
    RED(17),
    BLUE(18);

    final int code;

    Colour(int code) {
      this.code = code + 19;
    }
  }
}

class Second {
  Object other() {
    return new Object() {
      int n = 20;
    };
  }
}
