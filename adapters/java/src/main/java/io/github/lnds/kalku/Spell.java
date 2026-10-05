package io.github.lnds.kalku;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The spells of the shared catalogue, by their wire names.
 *
 * <p>A language may add spells, but a new one gets a shared name through the protocol and not
 * an ad-hoc string, so this is the closed set the protocol states and nothing of Java's own.
 */
final class Spell {
  private Spell() {}

  static final String ARM = "arm";
  static final String COMPARE = "compare";
  static final String CONNECT = "connect";
  static final String NEGATE = "negate";
  static final String LITERAL = "literal";
  static final String CALL = "call";

  static final List<String> ALL =
      Collections.unmodifiableList(
          Arrays.asList(
              ARM, COMPARE, CONNECT, NEGATE, LITERAL, CALL, "await", "supervise", "foreign"));

  // What this kalku proposes. `await` and `supervise` are the BEAM's, and `foreign` is a
  // driver's; a spell is announced when it is true.
  static final List<String> CAST =
      Collections.unmodifiableList(Arrays.asList(ARM, COMPARE, CONNECT, NEGATE, LITERAL, CALL));

  static boolean known(String name) {
    return ALL.contains(name);
  }
}
