package io.github.lnds.kalku;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The protocol's JSON, encoded and decoded here rather than by a library.
 *
 * <p>The kalku runs on the project's own JDK and beside the project's own classes, so it brings
 * no dependency that could be a different version of one of theirs.
 *
 * <p>Decoding gives a {@code Map} (in the order written), a {@code List}, a {@code String}, a
 * {@code Long}, a {@code BigInteger} past a long, a {@code Double} for a number with a fraction
 * or an exponent, a {@code Boolean}, or {@code null}. Encoding is canonical: no whitespace, raw
 * UTF-8, and only {@code "}, {@code \} and control characters escaped. A real is never encoded:
 * nothing this kalku says holds one, and the shortest form of a double is not the same text on
 * every JDK.
 */
final class Json {
  private Json() {}

  /** Text that is not one JSON document. */
  static final class Invalid extends Exception {
    private static final long serialVersionUID = 1L;

    Invalid(String why) {
      super(why);
    }
  }

  // A document nested deeper than this is refused rather than followed off the stack.
  private static final int MAX_DEPTH = 256;

  // ---- encode --------------------------------------------------------------

  static String encode(Object value) {
    StringBuilder out = new StringBuilder();
    write(out, value);
    return out.toString();
  }

  private static void write(StringBuilder out, Object value) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String) {
      string(out, (String) value);
    } else if (value instanceof Boolean || value instanceof Long || value instanceof Integer) {
      out.append(value);
    } else if (value instanceof Map) {
      object(out, (Map<?, ?>) value);
    } else if (value instanceof Collection) {
      array(out, (Collection<?>) value);
    } else {
      throw new IllegalArgumentException("not a JSON value: " + value.getClass().getName());
    }
  }

  private static void object(StringBuilder out, Map<?, ?> members) {
    out.append('{');
    boolean first = true;
    for (Map.Entry<?, ?> member : members.entrySet()) {
      if (!first) {
        out.append(',');
      }
      first = false;
      string(out, (String) member.getKey());
      out.append(':');
      write(out, member.getValue());
    }
    out.append('}');
  }

  private static void array(StringBuilder out, Collection<?> items) {
    out.append('[');
    boolean first = true;
    for (Object item : items) {
      if (!first) {
        out.append(',');
      }
      first = false;
      write(out, item);
    }
    out.append(']');
  }

  private static void string(StringBuilder out, String text) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
          break;
        case '\b':
          out.append("\\b");
          break;
        case '\t':
          out.append("\\t");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\f':
          out.append("\\f");
          break;
        case '\r':
          out.append("\\r");
          break;
        default:
          if (c < 0x20) {
            out.append(String.format("\\u%04X", (int) c));
          } else {
            out.append(c);
          }
      }
    }
    out.append('"');
  }

  // ---- decode --------------------------------------------------------------

  static Object decode(String text) throws Invalid {
    Reader reader = new Reader(text);
    reader.skip();
    Object value = reader.value(0);
    reader.skip();
    if (reader.at < text.length()) {
      throw new Invalid("something follows the document");
    }
    return value;
  }

  private static final class Reader {
    private final String text;
    private int at;

    Reader(String text) {
      this.text = text;
    }

    void skip() {
      while (at < text.length()) {
        char c = text.charAt(at);
        if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
          return;
        }
        at++;
      }
    }

    Object value(int depth) throws Invalid {
      if (depth > MAX_DEPTH) {
        throw new Invalid("nested too deeply");
      }
      if (at >= text.length()) {
        throw new Invalid("the document ends early");
      }
      char c = text.charAt(at);
      if (c == '{') {
        at++;
        return object(depth);
      }
      if (c == '[') {
        at++;
        return array(depth);
      }
      if (c == '"') {
        at++;
        return string();
      }
      if (c == '-' || (c >= '0' && c <= '9')) {
        return number();
      }
      if (word("true")) {
        return Boolean.TRUE;
      }
      if (word("false")) {
        return Boolean.FALSE;
      }
      if (word("null")) {
        return null;
      }
      throw new Invalid("not a value at " + at);
    }

    private boolean word(String word) {
      if (text.startsWith(word, at)) {
        at += word.length();
        return true;
      }
      return false;
    }

    private Map<String, Object> object(int depth) throws Invalid {
      Map<String, Object> members = new LinkedHashMap<>();
      skip();
      if (take('}')) {
        return members;
      }
      while (true) {
        skip();
        expect('"');
        String key = string();
        skip();
        expect(':');
        skip();
        members.put(key, value(depth + 1));
        skip();
        if (take('}')) {
          return members;
        }
        expect(',');
      }
    }

    private List<Object> array(int depth) throws Invalid {
      List<Object> items = new ArrayList<>();
      skip();
      if (take(']')) {
        return items;
      }
      while (true) {
        skip();
        items.add(value(depth + 1));
        skip();
        if (take(']')) {
          return items;
        }
        expect(',');
      }
    }

    private boolean take(char c) {
      if (at < text.length() && text.charAt(at) == c) {
        at++;
        return true;
      }
      return false;
    }

    private void expect(char c) throws Invalid {
      if (!take(c)) {
        throw new Invalid("expected `" + c + "` at " + at);
      }
    }

    private String string() throws Invalid {
      StringBuilder out = new StringBuilder();
      while (true) {
        if (at >= text.length()) {
          throw new Invalid("a string is not closed");
        }
        char c = text.charAt(at++);
        if (c == '"') {
          return out.toString();
        }
        if (c < 0x20) {
          throw new Invalid("a control character inside a string");
        }
        if (c == '\\') {
          escape(out);
        } else if (Character.isSurrogate(c)) {
          pair(out, c);
        } else {
          out.append(c);
        }
      }
    }

    // A surrogate written raw must be half of a pair, as one written escaped must.
    private void pair(StringBuilder out, char high) throws Invalid {
      if (!Character.isHighSurrogate(high)
          || at >= text.length()
          || !Character.isLowSurrogate(text.charAt(at))) {
        throw new Invalid("half a surrogate pair");
      }
      out.append(high).append(text.charAt(at++));
    }

    private void escape(StringBuilder out) throws Invalid {
      if (at >= text.length()) {
        throw new Invalid("a string is not closed");
      }
      char c = text.charAt(at++);
      switch (c) {
        case '"':
        case '\\':
        case '/':
          out.append(c);
          break;
        case 'b':
          out.append('\b');
          break;
        case 'f':
          out.append('\f');
          break;
        case 'n':
          out.append('\n');
          break;
        case 'r':
          out.append('\r');
          break;
        case 't':
          out.append('\t');
          break;
        case 'u':
          unicode(out);
          break;
        default:
          throw new Invalid("an escape that is not one");
      }
    }

    private void unicode(StringBuilder out) throws Invalid {
      char unit = unit();
      if (Character.isLowSurrogate(unit)) {
        throw new Invalid("half a surrogate pair");
      }
      out.append(unit);
      if (Character.isHighSurrogate(unit)) {
        // A code point beyond the basic plane arrives as two escapes.
        if (!text.startsWith("\\u", at)) {
          throw new Invalid("half a surrogate pair");
        }
        at += 2;
        char low = unit();
        if (!Character.isLowSurrogate(low)) {
          throw new Invalid("half a surrogate pair");
        }
        out.append(low);
      }
    }

    private char unit() throws Invalid {
      if (at + 4 > text.length()) {
        throw new Invalid("an escape that is not one");
      }
      int code = 0;
      for (int i = 0; i < 4; i++) {
        int digit = Character.digit(text.charAt(at++), 16);
        if (digit < 0) {
          throw new Invalid("an escape that is not one");
        }
        code = code * 16 + digit;
      }
      return (char) code;
    }

    private Object number() throws Invalid {
      int start = at;
      take('-');
      if (!take('0')) {
        digits();
      }
      boolean real = false;
      if (take('.')) {
        real = true;
        digits();
      }
      if (take('e') || take('E')) {
        real = true;
        if (!take('+')) {
          take('-');
        }
        digits();
      }
      String written = text.substring(start, at);
      if (!real) {
        BigInteger whole = new BigInteger(written);
        return whole.bitLength() < Long.SIZE ? (Object) whole.longValueExact() : whole;
      }
      double value = Double.parseDouble(written);
      if (Double.isInfinite(value)) {
        throw new Invalid("a number too large to hold");
      }
      return value;
    }

    // At least one digit, or the number is not one.
    private void digits() throws Invalid {
      int start = at;
      while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') {
        at++;
      }
      if (at == start) {
        throw new Invalid("a number that is not one");
      }
    }
  }
}
