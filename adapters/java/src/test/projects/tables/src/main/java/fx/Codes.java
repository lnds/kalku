package fx;

import java.util.HashMap;
import java.util.Map;

public final class Codes {
  private Codes() {}

  // Filled once, when this class is first used: by whichever test happens to come first.
  private static final Map<String, Integer> TABLE = table();

  private static Map<String, Integer> table() {
    Map<String, Integer> codes = new HashMap<>();
    codes.put("a", 1);
    codes.put("b", 2);
    return codes;
  }

  public static boolean known(String name) {
    return name != null && TABLE.containsKey(name);
  }

  public static int of(String name) {
    return TABLE.get(name);
  }

  public static String label(int n) {
    return n > 2 ? "many" : "few";
  }
}
