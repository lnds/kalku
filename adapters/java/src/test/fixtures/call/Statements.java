package fx.call;

import java.util.ArrayList;
import java.util.List;

class Statements extends ArrayList<String> {
  private final List<String> seen = new ArrayList<>();

  Statements() {
    this(4);
  }

  Statements(int n) {
    super(n);
    seen.add("made");
  }

  // A call whose value nobody takes can be left out.
  void record(String s) {
    seen.add(s);
    notifyAll(s);
    this.seen.clear();
  }

  // A call whose value is used is not this spell's.
  int used(String s) {
    int n = seen.indexOf(s);
    return Math.max(n, 0);
  }

  // Without braces, an empty statement keeps the `if` to itself.
  void guarded(boolean on, String s) {
    if (on) record(s);
    seen.add("after");
  }

  // The header of a `for` is not a place for an empty statement: nothing is proposed there.
  void loop(java.util.Iterator<String> it) {
    for (int i = 0; i < 3; it.next()) {
      i++;
    }
    for (it.next(); it.hasNext(); ) {
      it.remove();
    }
  }

  // A lambda's expression is a value, not a statement; a lambda's block has statements.
  Runnable later(String s) {
    Runnable brief = () -> record(s);
    return () -> {
      brief.run();
    };
  }

  void chained(StringBuilder b) {
    b.append("a").append("b");
    new Statements().record("x");
    super.clear();
  }

  // In the body of a `try` with a `catch`, a call may be the one thing that throws what is
  // caught: it stays. In the `catch` and the `finally` it is a site again.
  void close(java.io.Reader in) {
    try {
      in.close();
    } catch (java.io.IOException e) {
      seen.add("failed");
    } finally {
      seen.add("closed");
    }
  }

  private void notifyAll(String s) {}
}
