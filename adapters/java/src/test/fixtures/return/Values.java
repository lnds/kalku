package fx.ret;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

class Values {
  @interface NonNull {}

  static class Item {}

  private final int count;

  // A constructor gives nothing back.
  Values(int count) {
    this.count = count;
  }

  // Numeric primitives give back 0, `char` among them.
  int count() {
    return count + 1;
  }

  long total() {
    return count * 10L;
  }

  char initial() {
    return 'k';
  }

  double ratio() {
    return count / 2.0;
  }

  // A condition gives back `false`; a `true` or `false` written as such is `literal`'s.
  boolean big() {
    if (count > 9) {
      return true;
    }
    return count > 5;
  }

  // Boxed, a value of the same kind.
  Integer boxed() {
    return count;
  }

  Long boxedLong() {
    return (long) count;
  }

  Boolean known() {
    return count > 0;
  }

  String name() {
    return "kalku" + count;
  }

  Optional<String> label() {
    return Optional.of(name());
  }

  java.util.OptionalInt maybe() {
    return java.util.OptionalInt.of(count);
  }

  // The empty ones of java.util.Collections, which every release has.
  List<String> names() {
    List<String> out = new ArrayList<>();
    out.add(name());
    return out;
  }

  Set<Integer> numbers() {
    Set<Integer> out = new TreeSet<>();
    out.add(count);
    return out;
  }

  Map<String, Integer> index() {
    Map<String, Integer> out = new TreeMap<>();
    out.put(name(), count);
    return out;
  }

  Collection<String> all() {
    return names();
  }

  Iterable<String> walk() {
    return names();
  }

  // Any other reference type gives back null.
  Item item() {
    return new Item();
  }

  int[] digits() {
    return new int[] {count};
  }

  <T> T first(List<T> xs) {
    return xs.get(0);
  }

  // Unless the method says it never does.
  @NonNull
  Item sure() {
    return new Item();
  }

  // Already the neutral value: nothing to change.
  int none() {
    return 0;
  }

  String empty() {
    return "";
  }

  Item nothing() {
    return null;
  }

  Optional<String> unlabelled() {
    return Optional.empty();
  }

  // Nothing comes back from a `void` method, and 0 is as good a hash as any.
  void touch() {
    if (count > 0) {
      return;
    }
  }

  @Override
  public int hashCode() {
    return count * 31;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Values && ((Values) other).count == count;
  }

  // A lambda's `return` is the lambda's; the method's own is still one.
  Supplier<String> later() {
    Supplier<String> s =
        () -> {
          return name();
        };
    return s;
  }

  // In a `try` with a `catch`, a call may be the one thing that throws what is caught: a
  // `return` that makes one stays. One that makes none is still a site.
  static String read(java.io.Reader in) {
    try {
      return String.valueOf(in.read());
    } catch (java.io.IOException e) {
      return "";
    }
  }

  int counted() {
    try {
      touch();
      return count;
    } catch (RuntimeException e) {
      return -1;
    }
  }
}
