package fx.compare;

import java.util.List;
import java.util.Map;

class Comparisons {
  boolean all(int a, int b) {
    boolean ge = a >= b;
    boolean gt = a > b;
    boolean le = a <= b;
    boolean lt = a < b;
    boolean eq = a == b;
    boolean ne = a != b;
    return ge && gt && le && lt && eq && ne;
  }

  // The operator is found past a comment, not inside it.
  boolean commented(int a, int b) {
    return a /* >= */ > // <
        b;
  }

  // The angle brackets of a type are not comparisons.
  int generics(Map<String, List<Integer>> m) {
    List<Integer> xs = m.get("k");
    return xs.size() > 2 ? 1 : 0;
  }

  boolean inLambda(List<Integer> xs) {
    return xs.stream().anyMatch(x -> x >= 10);
  }

  boolean parenthesised(int a, int b) {
    return (a) == (b);
  }

  boolean nullCheck(Object o) {
    return o != null;
  }
}
