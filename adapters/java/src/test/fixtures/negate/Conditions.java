package fx.negate;

import java.util.List;

class Conditions {
  int plain(boolean ready) {
    if (ready) {
      return 1;
    }
    return 0;
  }

  // Already negated: the `!` is dropped, and nothing is wrapped around it.
  int negated(List<String> xs) {
    if (!xs.isEmpty()) {
      return xs.size();
    }
    return 0;
  }

  int compound(int a, int b) {
    if (a > 0 && b > 0) {
      return a;
    } else if (a == b) {
      return b;
    }
    return 0;
  }

  // A `!` anywhere is a site; a loop's condition is not wrapped.
  int loop(List<String> xs) {
    int n = 0;
    while (n < xs.size()) {
      boolean skip = !(xs.get(n).isEmpty() || n > 3);
      n++;
    }
    return n;
  }

  // `~` and `-` are not negations of a condition.
  int arithmetic(int a) {
    return ~a + -a;
  }
}
