package io.github.lnds.kalku;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Where a defect can be cast in one Java file.
 *
 * <p>The parser is the JDK's own, the one that will compile the project, reached through the
 * Compiler Tree API and nothing private to one release. So the grammar is always the project's:
 * a file this JDK cannot read is skipped with the compiler's own words, never read by rules
 * that may not be its own. And this never scans text for sites: a literal inside a comment is
 * not a node and cannot become one.
 *
 * <p>The tree says what and where; the replacement is a span of the original text. A candidate
 * whose text cannot be pinned down exactly yields no site rather than a guess. Every form is
 * chosen so that what it leaves is still a Java file, which is not checked here, one parse per
 * candidate, but by the suite: every site of every fixture and of this kalku's own sources is
 * spliced and parsed.
 *
 * <p>A construct newer than Java 11 has no type this code can name, because it is compiled for
 * 11. It is reached all the same: the running JDK's own scanner walks it, and it is told apart
 * by the name of its kind.
 *
 * <p>Not proposed, on purpose:
 *
 * <ul>
 *   <li>annotations and the default of an annotation's element: metadata, not behaviour;
 *   <li>{@code serialVersionUID}, which no test of the program can tell apart;
 *   <li>the labels of a {@code case}, whose literals are structure and not values;
 *   <li>a condition that binds a pattern variable, where negating or reconnecting it changes
 *       what is in scope and the compiler, not a test, is what objects;
 *   <li>{@code this(...)} and {@code super(...)}, which a constructor cannot go without;
 *   <li>the arguments of a call that {@code exclude_calls} names;
 *   <li>test files, which the service refuses before it gets here.
 * </ul>
 */
final class Sites {
  private Sites() {}

  /** One place a defect can be cast. */
  static final class Site {
    String siteId;
    String file;
    String enclosing;
    int ordinal;
    Source.Position start;
    Source.Position end;
    String spell;
    String original;
    String replacement;
  }

  /** A file that cannot be searched. */
  static final class ParseError extends Exception {
    private static final long serialVersionUID = 1L;

    ParseError(String why) {
      super(why);
    }
  }

  private static final class Candidate {
    final String spell;
    final int start;
    final int end;
    final String replacement;
    final String enclosing;

    Candidate(String spell, int start, int end, String replacement, String enclosing) {
      this.spell = spell;
      this.start = start;
      this.end = end;
      this.replacement = replacement;
      this.enclosing = enclosing;
    }
  }

  private static final Comparator<Candidate> IN_SOURCE_ORDER =
      Comparator.<Candidate>comparingInt(c -> c.start)
          .thenComparingInt(c -> c.end)
          .thenComparing(c -> c.spell)
          .thenComparing(c -> c.replacement);

  /** True when this runtime has a compiler: a JRE without {@code jdk.compiler} has none. */
  static boolean compilerPresent() {
    return ToolProvider.getSystemJavaCompiler() != null;
  }

  /** Searches one file. */
  static List<Site> find(String file, String text, Set<String> spells, List<String> excludeCalls)
      throws ParseError {
    Source src = new Source(text);
    Parsed parsed = parse(text);
    Walker walker = new Walker(parsed, src, spells, excludeCalls);
    walker.scan(parsed.unit, null);

    Map<String, Candidate> distinct = new HashMap<>();
    for (Candidate c : walker.found) {
      distinct.put(c.start + "|" + c.end + "|" + c.spell + "|" + c.replacement, c);
    }
    List<Candidate> resolved = new ArrayList<>(distinct.values());
    resolved.sort(IN_SOURCE_ORDER);

    // A wekufe has to differ from the original.
    resolved.removeIf(c -> src.slice(c.start, c.end).equals(c.replacement));
    return number(file, src, resolved);
  }

  // Ordinal: the 1-based occurrence of `(enclosing, spell, original)` in source order. It is
  // what lets a declared equivalent survive an edit elsewhere in the file, where a line number
  // would not.
  private static List<Site> number(String file, Source src, List<Candidate> kept) {
    String fileHash = sha256(src.data);
    Map<String, Integer> seen = new HashMap<>();
    List<Site> sites = new ArrayList<>();
    for (Candidate c : kept) {
      Site s = new Site();
      s.file = file;
      s.enclosing = c.enclosing;
      s.spell = c.spell;
      s.original = src.slice(c.start, c.end);
      s.replacement = c.replacement;
      s.start = src.position(c.start);
      s.end = src.position(c.end);
      s.ordinal = seen.merge(c.enclosing + "\u0000" + c.spell + "\u0000" + s.original, 1, Integer::sum);
      String key = fileHash + "|" + s.start.at + "|" + s.end.at + "|" + c.spell + "|" + c.replacement;
      s.siteId = sha256(key.getBytes(StandardCharsets.UTF_8)).substring(0, 12);
      sites.add(s);
    }
    return sites;
  }

  private static String sha256(byte[] data) {
    try {
      StringBuilder out = new StringBuilder();
      for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) {
        out.append(String.format("%02x", b));
      }
      return out.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---- parsing ---------------------------------------------------------------

  private static final class Parsed {
    final CompilationUnitTree unit;
    final SourcePositions positions;

    Parsed(CompilationUnitTree unit, SourcePositions positions) {
      this.unit = unit;
      this.positions = positions;
    }
  }

  private static final class InMemory extends SimpleJavaFileObject {
    private final String text;

    InMemory(String text) {
      super(URI.create("string:///Source.java"), Kind.SOURCE);
      this.text = text;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return text;
    }
  }

  // The parser recovers from an error and still returns a tree. A tree it had to guess at is
  // not searched: one error and the file is skipped, with what the compiler said first.
  private static Parsed parse(String text) throws ParseError {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new ParseError("this Java runtime has no compiler (module jdk.compiler)");
    }
    DiagnosticCollector<JavaFileObject> said = new DiagnosticCollector<>();
    JavacTask task =
        (JavacTask)
            compiler.getTask(
                null,
                null,
                said,
                Collections.singletonList("-proc:none"),
                null,
                Collections.singletonList(new InMemory(text)));
    Iterator<? extends CompilationUnitTree> units;
    try {
      units = task.parse().iterator();
    } catch (IOException | RuntimeException e) {
      throw new ParseError(String.valueOf(e.getMessage()));
    }
    for (Diagnostic<? extends JavaFileObject> d : said.getDiagnostics()) {
      if (d.getKind() == Diagnostic.Kind.ERROR) {
        throw new ParseError("line " + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT));
      }
    }
    if (!units.hasNext()) {
      throw new ParseError("the compiler returned nothing");
    }
    return new Parsed(units.next(), Trees.instance(task).getSourcePositions());
  }

  // ---- the walk --------------------------------------------------------------

  private static final Pattern DECIMAL = Pattern.compile("-?(0|[1-9][0-9]*)[lL]?");
  private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);
  private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

  private static final Map<Tree.Kind, String> COMPARE = new TreeMap<>();
  private static final Map<String, String> COMPARE_TO = new HashMap<>();

  static {
    COMPARE.put(Tree.Kind.GREATER_THAN_EQUAL, ">=");
    COMPARE.put(Tree.Kind.GREATER_THAN, ">");
    COMPARE.put(Tree.Kind.LESS_THAN_EQUAL, "<=");
    COMPARE.put(Tree.Kind.LESS_THAN, "<");
    COMPARE.put(Tree.Kind.EQUAL_TO, "==");
    COMPARE.put(Tree.Kind.NOT_EQUAL_TO, "!=");
    COMPARE_TO.put(">=", ">");
    COMPARE_TO.put(">", ">=");
    COMPARE_TO.put("<=", "<");
    COMPARE_TO.put("<", "<=");
    COMPARE_TO.put("==", "!=");
    COMPARE_TO.put("!=", "==");
  }

  private static final class Walker extends TreeScanner<Void, Void> {
    private final CompilationUnitTree unit;
    private final SourcePositions positions;
    private final Source src;
    private final Set<String> spells;
    private final List<String> exclude;
    private final String pack;
    // The declarations the walk is inside of, outermost first.
    private final Deque<String> scope = new ArrayDeque<>();
    private final Map<Tree, Boolean> skipped = new IdentityHashMap<>();
    // Statements that are the whole body of a `case ... ->`, where a statement has to stand.
    private final Map<Tree, Boolean> ruleBodies = new IdentityHashMap<>();
    private int anonymous;
    final List<Candidate> found = new ArrayList<>();

    Walker(Parsed parsed, Source src, Set<String> spells, List<String> exclude) {
      this.unit = parsed.unit;
      this.positions = parsed.positions;
      this.src = src;
      this.spells = spells;
      this.exclude = exclude;
      ExpressionTree name = parsed.unit.getPackageName();
      this.pack = name == null ? "" : compact(text(name));
    }

    // ---- helpers -------------------------------------------------------------

    private int start(Tree t) {
      return (int) positions.getStartPosition(unit, t);
    }

    private int end(Tree t) {
      return (int) positions.getEndPosition(unit, t);
    }

    // The compiler does not have an end for every node. One without it is not guessed at.
    private boolean placed(Tree t) {
      return t != null && start(t) >= 0 && end(t) >= start(t) && end(t) <= src.text.length();
    }

    private String text(Tree t) {
      return placed(t) ? src.slice(start(t), end(t)) : "";
    }

    private static String compact(String text) {
      return text.replaceAll("\\s+", "");
    }

    private String enclosing() {
      List<String> parts = new ArrayList<>();
      if (!pack.isEmpty()) {
        parts.add(pack);
      }
      for (Iterator<String> it = scope.descendingIterator(); it.hasNext(); ) {
        parts.add(it.next());
      }
      return parts.isEmpty() ? null : String.join(".", parts);
    }

    private void propose(String spell, int start, int end, String replacement) {
      if (spells.contains(spell) && start >= 0 && end >= start) {
        found.add(new Candidate(spell, start, end, replacement, enclosing()));
      }
    }

    // Past whitespace and comments, from `at` and never beyond `limit`.
    private int pastTrivia(int at, int limit) {
      String s = src.text;
      while (at < limit) {
        char c = s.charAt(at);
        if (Character.isWhitespace(c)) {
          at++;
        } else if (s.startsWith("//", at)) {
          while (at < limit && s.charAt(at) != '\n' && s.charAt(at) != '\r') {
            at++;
          }
        } else if (s.startsWith("/*", at)) {
          int close = s.indexOf("*/", at + 2);
          at = close < 0 ? limit : close + 2;
        } else {
          break;
        }
      }
      return Math.min(at, limit);
    }

    // The children the running JDK's own scanner would visit, without going into them.
    private static List<Tree> children(Tree parent) {
      List<Tree> out = new ArrayList<>();
      parent.accept(
          new TreeScanner<Void, Void>() {
            @Override
            public Void scan(Tree child, Void p) {
              if (child != null) {
                out.add(child);
              }
              return null;
            }
          },
          null);
      return out;
    }

    private static boolean kindIs(Tree t, String name) {
      return t.getKind().name().equals(name);
    }

    // A pattern binds a name whose scope follows from the truth of the condition around it.
    private static boolean bindsPattern(Tree t) {
      boolean[] found = {false};
      new TreeScanner<Void, Void>() {
        @Override
        public Void scan(Tree child, Void p) {
          if (child != null && child.getKind().name().endsWith("_PATTERN")) {
            found[0] = true;
          }
          return found[0] ? null : super.scan(child, p);
        }
      }.scan(t, null);
      return found[0];
    }

    // ---- the tree --------------------------------------------------------------

    @Override
    public Void scan(Tree t, Void p) {
      if (t == null || skipped.containsKey(t)) {
        return null;
      }
      if (kindIs(t, "SWITCH") || kindIs(t, "SWITCH_EXPRESSION")) {
        arms(t);
      }
      return super.scan(t, p);
    }

    @Override
    public Void visitAnnotation(AnnotationTree t, Void p) {
      return null;
    }

    @Override
    public Void visitClass(ClassTree t, Void p) {
      if (scope.isEmpty()) {
        anonymous = 0;
      }
      String name = t.getSimpleName().toString();
      scope.push(name.isEmpty() ? "$" + (++anonymous) : name);
      try {
        return super.visitClass(t, p);
      } finally {
        scope.pop();
      }
    }

    @Override
    public Void visitMethod(MethodTree t, Void p) {
      List<String> types = new ArrayList<>();
      for (VariableTree parameter : t.getParameters()) {
        types.add(compact(text(parameter.getType())));
      }
      if (t.getDefaultValue() != null) {
        skipped.put(t.getDefaultValue(), true);
      }
      scope.push(t.getName() + "(" + String.join(",", types) + ")");
      try {
        return super.visitMethod(t, p);
      } finally {
        scope.pop();
      }
    }

    // The version stamp of a serialised form is read by the runtime when an object from another
    // build arrives, never by the program: a wekufe there is one no test could kill.
    @Override
    public Void visitVariable(VariableTree t, Void p) {
      if (t.getName().contentEquals("serialVersionUID") && t.getInitializer() != null) {
        skipped.put(t.getInitializer(), true);
      }
      return super.visitVariable(t, p);
    }

    // `arm`: delete one case, only where a `default` remains, because without one a switch
    // that has to be exhaustive no longer is, and the compiler is what rejects it.
    private void arms(Tree switchTree) {
      List<Tree> cases = new ArrayList<>();
      for (Tree child : children(switchTree)) {
        if (child instanceof CaseTree) {
          cases.add(child);
        }
      }
      boolean hasDefault = false;
      for (Tree c : cases) {
        hasDefault |= isDefault(c);
      }
      if (!hasDefault || cases.size() < 2) {
        return;
      }
      for (Tree c : cases) {
        if (!isDefault(c) && placed(c)) {
          propose(Spell.ARM, start(c), end(c), "");
        }
      }
    }

    private boolean isDefault(Tree caseTree) {
      if (text(caseTree).startsWith("default")) {
        return true;
      }
      for (Tree child : children(caseTree)) {
        if (kindIs(child, "DEFAULT_CASE_LABEL")) {
          return true;
        }
      }
      return false;
    }

    // What a case is matched against is structure. Its guard and its body are code.
    @Override
    public Void visitCase(CaseTree t, Void p) {
      int from = start(t);
      for (Tree child : children(t)) {
        String lead = leadTo(child, from);
        if (child instanceof StatementTree) {
          if (lead.equals("->")) {
            ruleBodies.put(child, true);
          }
        } else if (child.getKind().name().endsWith("_CASE_LABEL") || lead.isEmpty()) {
          // An expression directly under a case is a label unless `->` or `when` leads to it.
          skipped.put(child, true);
        }
        if (placed(child)) {
          from = end(child);
        }
      }
      return super.visitCase(t, p);
    }

    // `->` or `when` when one of them is what comes right before a child of a case, past the
    // case's own keyword; otherwise nothing.
    private String leadTo(Tree child, int from) {
      if (!placed(child) || from < 0) {
        return "";
      }
      String s = src.text;
      int at = pastTrivia(from, start(child));
      for (String keyword : new String[] {"case", "default"}) {
        if (s.startsWith(keyword, at)) {
          at = pastTrivia(at + keyword.length(), start(child));
        }
      }
      return s.startsWith("->", at) ? "->" : s.startsWith("when", at) ? "when" : "";
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree t, Void p) {
      return excluded(t) ? null : super.visitMethodInvocation(t, p);
    }

    private boolean excluded(MethodInvocationTree call) {
      String name = dotted(call.getMethodSelect());
      for (String pattern : exclude) {
        if (matches(name, pattern)) {
          return true;
        }
      }
      return false;
    }

    // `log.info` for the method of a call, or an empty name when it is not plain names.
    private static String dotted(Tree select) {
      if (select instanceof IdentifierTree) {
        return ((IdentifierTree) select).getName().toString();
      }
      if (select instanceof MemberSelectTree) {
        MemberSelectTree member = (MemberSelectTree) select;
        String owner = dotted(member.getExpression());
        return owner.isEmpty() ? "" : owner + "." + member.getIdentifier();
      }
      return "";
    }

    // `exclude_calls` patterns: an exact name, or a prefix ending in `*`.
    private static boolean matches(String name, String pattern) {
      if (name.isEmpty()) {
        return false;
      }
      int star = pattern.indexOf('*');
      if (star < 0) {
        return name.equals(pattern);
      }
      return star == pattern.length() - 1 && name.startsWith(pattern.substring(0, star));
    }

    // `call`: a call whose value nobody takes is left out. An empty statement stands where it
    // was, so what follows it is still what follows it. Not where no empty statement can
    // stand: the header of a `for`, whose calls end without a semicolon, and the body of a
    // `case ... ->`.
    @Override
    public Void visitExpressionStatement(ExpressionStatementTree t, Void p) {
      ExpressionTree e = t.getExpression();
      if (e instanceof MethodInvocationTree
          && placed(t)
          && text(t).endsWith(";")
          && !ruleBodies.containsKey(t)) {
        MethodInvocationTree call = (MethodInvocationTree) e;
        String name = dotted(call.getMethodSelect());
        if (!name.equals("this") && !name.equals("super") && !excluded(call)) {
          propose(Spell.CALL, start(t), end(t), ";");
        }
      }
      return super.visitExpressionStatement(t, p);
    }

    @Override
    public Void visitBinary(BinaryTree t, Void p) {
      String compare = COMPARE.get(t.getKind());
      if (compare != null) {
        operator(t, compare, COMPARE_TO.get(compare), Spell.COMPARE);
      } else if (!bindsPattern(t)) {
        if (t.getKind() == Tree.Kind.CONDITIONAL_AND) {
          operator(t, "&&", "||", Spell.CONNECT);
        } else if (t.getKind() == Tree.Kind.CONDITIONAL_OR) {
          operator(t, "||", "&&", Spell.CONNECT);
        }
      }
      return super.visitBinary(t, p);
    }

    // The tree does not keep where an operator sits. It is the first thing after the left
    // operand that is neither whitespace nor a comment, and it has to read as expected.
    private void operator(BinaryTree t, String is, String becomes, String spell) {
      if (!placed(t.getLeftOperand()) || !placed(t.getRightOperand())) {
        return;
      }
      int limit = start(t.getRightOperand());
      int at = pastTrivia(end(t.getLeftOperand()), limit);
      if (at + is.length() <= limit && src.text.startsWith(is, at)) {
        propose(spell, at, at + is.length(), becomes);
      }
    }

    // `negate`, one way: a `!` is dropped.
    @Override
    public Void visitUnary(UnaryTree t, Void p) {
      if (t.getKind() == Tree.Kind.LOGICAL_COMPLEMENT
          && placed(t)
          && placed(t.getExpression())
          && !bindsPattern(t)) {
        propose(Spell.NEGATE, start(t), end(t), text(t.getExpression()));
      }
      return super.visitUnary(t, p);
    }

    // `negate`, the other way: the condition of an `if` gains a `!`. One that already starts
    // with `!` is left to the site above, which is the same wekufe.
    @Override
    public Void visitIf(IfTree t, Void p) {
      ExpressionTree condition = t.getCondition();
      if (condition instanceof ParenthesizedTree) {
        ExpressionTree inner = ((ParenthesizedTree) condition).getExpression();
        if (placed(inner)
            && inner.getKind() != Tree.Kind.LOGICAL_COMPLEMENT
            && !bindsPattern(inner)) {
          propose(Spell.NEGATE, start(inner), end(inner), "!(" + text(inner) + ")");
        }
      }
      return super.visitIf(t, p);
    }

    @Override
    public Void visitLiteral(LiteralTree t, Void p) {
      if (!placed(t)) {
        return null;
      }
      String written = text(t);
      switch (t.getKind()) {
        case BOOLEAN_LITERAL:
          if (written.equals("true") || written.equals("false")) {
            propose(Spell.LITERAL, start(t), end(t), written.equals("true") ? "false" : "true");
          }
          break;
        case INT_LITERAL:
          next(t, written, INT_MAX);
          break;
        case LONG_LITERAL:
          next(t, written, LONG_MAX);
          break;
        case STRING_LITERAL:
          if (t.getValue() instanceof String && !((String) t.getValue()).isEmpty()) {
            propose(Spell.LITERAL, start(t), end(t), "\"\"");
          }
          break;
        default:
          break;
      }
      return null;
    }

    // `n` becomes `n + 1`, for a plain decimal whose successor is still in its type's range.
    // Hexadecimal, octal, binary and underscored forms are left alone: one grammar to be right
    // about.
    private void next(LiteralTree t, String written, BigInteger max) {
      if (!DECIMAL.matcher(written).matches()) {
        return;
      }
      boolean suffixed = written.endsWith("l") || written.endsWith("L");
      String digits = suffixed ? written.substring(0, written.length() - 1) : written;
      BigInteger successor = new BigInteger(digits).add(BigInteger.ONE);
      if (successor.compareTo(max) > 0) {
        return;
      }
      String suffix = suffixed ? written.substring(written.length() - 1) : "";
      propose(Spell.LITERAL, start(t), end(t), successor + suffix);
    }
  }
}
