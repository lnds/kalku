package io.github.lnds.kalku;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a {@code return} wekufe gives back instead of what the method computed: the neutral
 * value of the method's declared return type, written so that it compiles where the original
 * stood. The type is read as written, since the parser has no other; a type it cannot name a
 * value for yields no site.
 *
 * <ul>
 *   <li>a numeric primitive, {@code char} among them, gives {@code 0}, and {@code boolean} gives
 *       {@code false};
 *   <li>a boxed primitive gives the same value, boxed;
 *   <li>{@code String} gives {@code ""}; {@code Optional} and its primitive kin give {@code
 *       empty()}; {@code List}, {@code Set}, {@code Map}, {@code Collection} and {@code
 *       Iterable} give the empty one of {@code java.util.Collections}, which every release of
 *       Java has, when the name is {@code java.util}'s by its imports;
 *   <li>any other reference type gives {@code null}, but for a method annotated as never
 *       returning one.
 * </ul>
 *
 * <p>Not proposed: a constructor or a {@code void} method, which return nothing; a value that
 * already is the neutral one, or {@code null}; {@code true} and {@code false}, a string literal
 * where the neutral value is {@code ""}, and {@code -1} where it is {@code 0}, which the {@code
 * literal} spell already changes the same way; {@code hashCode()}, for which {@code 0} is as valid an answer
 * as any; the {@code return} of a lambda, which is the lambda's own; and, in the body of a
 * {@code try} with a {@code catch}, a value that calls a method or a constructor.
 */
final class Returns {
  private Returns() {}

  private static final Map<String, String> BOXED = new HashMap<>();
  private static final Map<String, String> EMPTY = new HashMap<>();
  private static final Set<String> OPTIONALS =
      new HashSet<>(Arrays.asList("Optional", "OptionalInt", "OptionalLong", "OptionalDouble"));

  static {
    BOXED.put("Integer", "0");
    BOXED.put("Long", "0L");
    BOXED.put("Double", "0.0");
    BOXED.put("Float", "0.0f");
    BOXED.put("Short", "(short) 0");
    BOXED.put("Byte", "(byte) 0");
    BOXED.put("Character", "'\\0'");
    BOXED.put("Boolean", "false");
    BOXED.put("String", "\"\"");
    EMPTY.put("List", "emptyList()");
    EMPTY.put("Collection", "emptyList()");
    EMPTY.put("Iterable", "emptyList()");
    EMPTY.put("Set", "emptySet()");
    EMPTY.put("Map", "emptyMap()");
  }

  /**
   * The neutral value of what {@code method} returns, or an empty string when there is none to
   * write. {@code type} is the return type as written, without whitespace; {@code imports} the
   * names the file imports, wildcards ending in {@code .*}.
   */
  static String neutral(MethodTree method, String type, Set<String> imports) {
    Tree returned = method.getReturnType();
    if (returned == null) {
      return "";
    }
    if (method.getName().contentEquals("hashCode") && method.getParameters().isEmpty()) {
      return "";
    }
    if (returned instanceof PrimitiveTypeTree) {
      switch (((PrimitiveTypeTree) returned).getPrimitiveTypeKind()) {
        case VOID:
          return "";
        case BOOLEAN:
          return "false";
        default:
          return "0";
      }
    }
    String raw = erased(type);
    String simple = raw.startsWith("java.lang.") ? raw.substring("java.lang.".length()) : raw;
    if (BOXED.containsKey(simple)) {
      return BOXED.get(simple);
    }
    String util = simple.equals("Iterable") ? simple : utilName(raw, imports);
    if (OPTIONALS.contains(util)) {
      return raw + ".empty()";
    }
    if (EMPTY.containsKey(util)) {
      return "java.util.Collections." + EMPTY.get(util);
    }
    return neverNull(method) ? "" : "null";
  }

  // `Map<K, V>` is `Map`, and `Item[]` stays an array: what is left of a type without its
  // arguments.
  private static String erased(String type) {
    int open = type.indexOf('<');
    return open < 0 ? type : type.substring(0, open) + type.substring(type.lastIndexOf('>') + 1);
  }

  // The simple name of a `java.util` type the way this file reaches it, or an empty string.
  private static String utilName(String raw, Set<String> imports) {
    if (raw.startsWith("java.util.")) {
      String rest = raw.substring("java.util.".length());
      return rest.contains(".") ? "" : rest;
    }
    if (raw.contains(".") || raw.contains("[")) {
      return "";
    }
    return imports.contains("java.util." + raw) || imports.contains("java.util.*") ? raw : "";
  }

  // A method that says it never returns null is held to it by tools a `null` wekufe would only
  // trip, and is not what a test is for.
  private static boolean neverNull(MethodTree method) {
    for (AnnotationTree a : method.getModifiers().getAnnotations()) {
      String name = a.getAnnotationType().toString();
      String simple = name.substring(name.lastIndexOf('.') + 1);
      if (simple.equals("NonNull") || simple.equals("Nonnull") || simple.equals("NotNull")) {
        return true;
      }
    }
    return false;
  }

  /**
   * True when {@code value}, written {@code written}, already is what {@code neutral} would give
   * back, or is a value another spell changes.
   */
  static boolean alreadyNeutral(ExpressionTree value, String written, String neutral) {
    if (written.equals(neutral)) {
      return true;
    }
    switch (value.getKind()) {
      case NULL_LITERAL:
      case BOOLEAN_LITERAL:
        return true;
      case STRING_LITERAL:
        return neutral.equals("\"\"") || "".equals(((LiteralTree) value).getValue());
      case INT_LITERAL:
      case LONG_LITERAL:
        long n = ((Number) ((LiteralTree) value).getValue()).longValue();
        return n == 0 || (n == -1 && written.matches("-1[lL]?") && neutral.startsWith("0"));
      case FLOAT_LITERAL:
      case DOUBLE_LITERAL:
        return ((Number) ((LiteralTree) value).getValue()).doubleValue() == 0;
      case CHAR_LITERAL:
        return Character.valueOf('\0').equals(((LiteralTree) value).getValue());
      default:
        return false;
    }
  }
}
