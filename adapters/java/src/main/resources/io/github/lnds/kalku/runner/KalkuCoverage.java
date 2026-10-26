package io.github.lnds.kalku.runner;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.analysis.ISourceFileCoverage;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Turns what the agent counted into which tests reach which places, for the Java kalku.
 *
 * <p>Like the runner, this travels as source and is compiled in the reni. It runs in a JVM of
 * its own, after the tests: reading what was counted needs a bytecode library, and that
 * library must never sit on the class path of the project's tests, where a project that uses
 * the same library would get a different version of it than its own build gives it.
 *
 * <p>The question it answers is asked place by place: a source file and a range of lines, the
 * statement a site is in. The answer errs one way only. Where the compiler gave those lines no
 * code — a constant, which it copies into the classes that use it — where the code ran outside
 * any one test, or where it ran as a class was first used, every test is named: a place wrongly
 * left without tests would never be cast and would drop out of the measure, and a place with
 * too many only costs time.
 *
 * <p>Given two more arguments, a list of class directories and a file, it also writes which
 * classes of those directories each test ran, and which every test may depend on: what ran
 * outside any one test or as a class was first used, and the classes with no code to run, which
 * a test uses without anything being counted. A fingerprint of what a test runs is taken over
 * these.
 */
public final class KalkuCoverage {
  private KalkuCoverage() {}

  private static final String ALL = "*";

  private static final class Place {
    String key;
    String source;
    int from;
    int to;
    boolean all;
    // Whether anything the agent counted fell in this place, in a test or outside one.
    boolean counted;
    final Set<String> tests = new LinkedHashSet<>();
  }

  public static void main(String[] args) throws IOException {
    Path dumps = Paths.get(args[0]);
    // The class directories the places are in: a module's own, and those of the modules it uses.
    Set<String> directories = new LinkedHashSet<>();
    Map<String, List<Place>> places = new HashMap<>();
    List<Place> inOrder = new ArrayList<>();
    for (String line : Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8)) {
      String[] part = line.split("\t");
      Place place = new Place();
      directories.add(part[0]);
      // The same source name can be in two modules: a place is told by its directory too.
      place.source = part[0] + "|" + part[1];
      place.from = Integer.parseInt(part[2]);
      place.to = Integer.parseInt(part[3]);
      place.key = part[4];
      places.computeIfAbsent(place.source, s -> new ArrayList<>()).add(place);
      inOrder.add(place);
    }
    // Every class directory a test may run classes of, the tests' own among them: a test class
    // may fill its static fields from the project's code as well.
    Set<String> analysed = new LinkedHashSet<>(directories);
    if (args.length > 5) {
      analysed.addAll(Files.readAllLines(Paths.get(args[4]), StandardCharsets.UTF_8));
    }
    Initialisers initialisers = new Initialisers(analysed);

    // Which lines have code at all, from the classes alone.
    Map<String, BitSet> coded = new HashMap<>();
    for (String directory : directories) {
      CoverageBuilder shape = new CoverageBuilder();
      new Analyzer(new ExecutionDataStore(), shape).analyzeAll(new File(directory));
      lines(shape, directory, coded);
    }
    for (Place place : inOrder) {
      BitSet code = coded.get(place.source);
      place.all = code == null || code.get(place.from, place.to + 1).isEmpty();
    }

    // What ran outside any one test: what a suite sets up, before and between its tests.
    Path outside = dumps.resolve("outside.exec");
    if (Files.isRegularFile(outside)) {
      Seen seen = seen(outside, analysed, initialisers);
      credit(places, seen.lines, place -> place.all = true);
    }

    // What ran while each test did. A method run many times is one test. What a class ran as
    // it was first used, during whichever test came first, belongs to every test.
    List<String> tests = Files.readAllLines(Paths.get(args[2]), StandardCharsets.UTF_8);
    Map<String, Seen> byTest = new HashMap<>();
    for (String line : tests) {
      String[] part = line.split("\t", 2);
      if (part.length < 2 || part[1].isEmpty()) {
        continue;
      }
      Seen seen = seen(dumps.resolve(part[0]), analysed, initialisers);
      byTest.put(part[0], seen);
      credit(places, seen.everyone, place -> place.all = true);
      credit(places, seen.lines, place -> place.tests.add(part[1]));
    }

    if (args.length > 5) {
      ran(dumps, tests, byTest, Paths.get(args[4]), Paths.get(args[5]));
    }

    try (Writer out = Files.newBufferedWriter(Paths.get(args[3]), StandardCharsets.UTF_8)) {
      // First, in how many places something was counted. A place with no code names every
      // test without anything having been counted, so an agent that counted nothing would
      // still leave a map that looks like one; this number is what tells the two apart.
      int counted = 0;
      for (Place place : inOrder) {
        counted += place.counted ? 1 : 0;
      }
      out.write("#counted\t" + counted + "\n");
      for (Place place : inOrder) {
        out.write(place.key);
        if (place.all) {
          out.write("\t" + ALL);
        } else {
          for (String test : place.tests) {
            out.write("\t" + test);
          }
        }
        out.write("\n");
      }
    }
  }

  // Every place some of these lines fall in was counted, and is given what `with` does.
  private static void credit(
      Map<String, List<Place>> places,
      Map<String, BitSet> lines,
      java.util.function.Consumer<Place> with) {
    for (Map.Entry<String, BitSet> reached : lines.entrySet()) {
      for (Place place : places.getOrDefault(reached.getKey(), new ArrayList<>())) {
        if (!reached.getValue().get(place.from, place.to + 1).isEmpty()) {
          place.counted = true;
          with.accept(place);
        }
      }
    }
  }

  // Lines of `test`, a class directory, and the name of a class in it the test ran; `*` for
  // the test when every test may depend on the class.
  private static void ran(
      Path dumps, List<String> tests, Map<String, Seen> byTest, Path listed, Path out)
      throws IOException {
    List<String> directories = Files.readAllLines(listed, StandardCharsets.UTF_8);
    try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
      for (String directory : directories) {
        for (String name : codeless(new File(directory))) {
          w.write(ALL + "\t" + directory + "\t" + name + "\n");
        }
      }
      Path outside = dumps.resolve("outside.exec");
      if (Files.isRegularFile(outside)) {
        written(w, ALL, hits(outside), directories);
      }
      for (String line : tests) {
        String[] part = line.split("\t", 2);
        if (part.length == 2 && !part[1].isEmpty()) {
          Seen seen = byTest.get(part[0]);
          written(w, ALL, seen.shared, directories);
          written(w, part[1], hits(dumps.resolve(part[0])), directories);
        }
      }
    }
  }

  private static void written(Writer w, String test, Set<String> names, List<String> directories)
      throws IOException {
    for (String name : names) {
      for (String directory : directories) {
        if (new File(directory, name + ".class").isFile()) {
          w.write(test + "\t" + directory + "\t" + name + "\n");
        }
      }
    }
  }

  // The classes something ran in, by their names in the counts.
  private static Set<String> hits(Path counted) throws IOException {
    ExecFileLoader loader = new ExecFileLoader();
    loader.load(counted.toFile());
    Set<String> out = new LinkedHashSet<>();
    for (ExecutionData data : loader.getExecutionDataStore().getContents()) {
      if (data.hasHits()) {
        out.add(data.getName());
      }
    }
    return out;
  }

  // The classes of a directory that have no code: an interface or an annotation with nothing
  // but declarations, and what the analysis passes over, such as a class the compiler made up.
  private static Set<String> codeless(File directory) throws IOException {
    Set<String> out = new java.util.TreeSet<>();
    Path root = directory.toPath();
    if (!Files.isDirectory(root)) {
      return out;
    }
    Set<String> coded = new java.util.HashSet<>();
    new Analyzer(
            new ExecutionDataStore(),
            (IClassCoverage c) -> {
              if (c.getInstructionCounter().getTotalCount() > 0) {
                coded.add(c.getName());
              }
            })
        .analyzeAll(directory);
    try (java.util.stream.Stream<Path> all = Files.walk(root)) {
      for (Path p : (Iterable<Path>) all::iterator) {
        String name = root.relativize(p).toString().replace('\\', '/');
        if (name.endsWith(".class")) {
          name = name.substring(0, name.length() - 6);
          if (!coded.contains(name)) {
            out.add(name);
          }
        }
      }
    }
    return out;
  }

  /** What one file of counts says: the lines that ran, and which of them every test owns. */
  private static final class Seen {
    // By class directory and source file, `dir|a/b/C.java`.
    final Map<String, BitSet> lines = new HashMap<>();
    // The lines that ran as a class was first used, which every test may depend on.
    final Map<String, BitSet> everyone = new HashMap<>();
    // The classes those lines are in, for the fingerprints.
    final Set<String> shared = new LinkedHashSet<>();
  }

  // The lines of each source file that ran in what one file of counts holds, and which of them
  // a class's initialiser may have run. A class whose initialiser ran here was first used here,
  // and what its initialiser reaches is told from its bytecode: a test that was first to use a
  // class is not the only one that depends on what the class did then.
  private static Seen seen(Path counted, Set<String> directories, Initialisers initialisers)
      throws IOException {
    ExecFileLoader loader = new ExecFileLoader();
    loader.load(counted.toFile());
    ExecutionDataStore store = loader.getExecutionDataStore();
    Map<String, List<IClassCoverage>> byDirectory = new HashMap<>();
    Set<String> initialised = new HashSet<>();
    for (String directory : directories) {
      CoverageBuilder builder = new CoverageBuilder();
      Analyzer analyzer = new Analyzer(store, builder);
      // Only the classes something ran in: the counts say which.
      for (ExecutionData data : store.getContents()) {
        File file = new File(directory, data.getName() + ".class");
        if (data.hasHits() && file.isFile()) {
          try (InputStream in = new FileInputStream(file)) {
            analyzer.analyzeClass(in, data.getName());
          }
        }
      }
      List<IClassCoverage> classes = new ArrayList<>(builder.getClasses());
      byDirectory.put(directory, classes);
      for (IClassCoverage c : classes) {
        for (IMethodCoverage m : c.getMethods()) {
          if (m.getName().equals("<clinit>") && m.getInstructionCounter().getCoveredCount() > 0) {
            initialised.add(c.getName());
          }
        }
      }
    }
    Initialisers.Reach reach = initialisers.of(initialised);
    Seen seen = new Seen();
    for (Map.Entry<String, List<IClassCoverage>> each : byDirectory.entrySet()) {
      for (IClassCoverage c : each.getValue()) {
        if (c.getSourceFileName() == null) {
          continue;
        }
        String source =
            each.getKey()
                + "|"
                + (c.getPackageName().isEmpty()
                    ? c.getSourceFileName()
                    : c.getPackageName() + "/" + c.getSourceFileName());
        for (IMethodCoverage m : c.getMethods()) {
          boolean shared = reach.opaque || reach.methods.contains(c.getName() + "." + m.getName() + m.getDesc());
          for (int line = m.getFirstLine(); line >= 0 && line <= m.getLastLine(); line++) {
            int status = m.getLine(line).getStatus();
            if (status == ICounter.FULLY_COVERED || status == ICounter.PARTLY_COVERED) {
              seen.lines.computeIfAbsent(source, s -> new BitSet()).set(line);
              if (shared) {
                seen.everyone.computeIfAbsent(source, s -> new BitSet()).set(line);
                seen.shared.add(c.getName());
              }
            }
          }
        }
      }
    }
    return seen;
  }

  // By class directory and source file, `dir|a/b/C.java`: the lines that have code.
  private static void lines(CoverageBuilder builder, String directory, Map<String, BitSet> out) {
    for (ISourceFileCoverage source : builder.getSourceFiles()) {
      String name =
          source.getPackageName().isEmpty()
              ? source.getName()
              : source.getPackageName() + "/" + source.getName();
      BitSet lines = out.computeIfAbsent(directory + "|" + name, n -> new BitSet());
      for (int line = source.getFirstLine(); line >= 0 && line <= source.getLastLine(); line++) {
        if (source.getLine(line).getStatus() != ICounter.EMPTY) {
          lines.set(line);
        }
      }
    }
  }

  /**
   * What a class's static initialiser may run, read from the project's bytecode.
   *
   * <p>A class runs its initialiser once in the life of a JVM, during whichever test happens to
   * use it first, and what it ran then — a table filled, a setting read — is there for every
   * test after it. The counts say which test that was; this says what of what that test ran
   * the initialiser may have run. It errs one way, toward too much: the methods the initialiser
   * calls, every override of them in the project, the initialiser of each class it uses, what
   * code outside the project may call back on an object it makes, the body of every lambda it
   * makes, and every method of a class it names by its literal. An initialiser that reaches
   * code by reflection — a class
   * named in a string, a method looked up, a service loaded — is not followed: everything the
   * test that ran it ran is then taken as the initialiser's.
   */
  private static final class Initialisers {
    static final class Reach {
      // `owner.name(desc)`, by internal names.
      final Set<String> methods = new HashSet<>();
      boolean opaque;
    }

    private final Map<String, ClassNode> classes = new HashMap<>();
    private final Map<String, List<String>> subtypes = new HashMap<>();
    private final Map<String, Reach> reaches = new HashMap<>();

    Initialisers(Collection<String> directories) throws IOException {
      for (String directory : directories) {
        Path root = Paths.get(directory);
        if (!Files.isDirectory(root)) {
          continue;
        }
        try (java.util.stream.Stream<Path> all = Files.walk(root)) {
          for (Path p : (Iterable<Path>) all::iterator) {
            if (p.toString().endsWith(".class")) {
              ClassNode node = new ClassNode();
              new ClassReader(Files.readAllBytes(p))
                  .accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
              classes.putIfAbsent(node.name, node);
            }
          }
        }
      }
      for (ClassNode node : classes.values()) {
        if (node.superName != null) {
          subtypes.computeIfAbsent(node.superName, s -> new ArrayList<>()).add(node.name);
        }
        for (String each : node.interfaces) {
          subtypes.computeIfAbsent(each, s -> new ArrayList<>()).add(node.name);
        }
      }
    }

    // What the initialisers of these classes may run, between them.
    Reach of(Set<String> initialised) {
      Reach out = new Reach();
      for (String name : initialised) {
        Reach one = reaches.computeIfAbsent(name, this::reach);
        out.methods.addAll(one.methods);
        out.opaque |= one.opaque;
      }
      return out;
    }

    private Reach reach(String name) {
      Reach reach = new Reach();
      Deque<Object[]> work = new ArrayDeque<>();
      initialiser(name, reach, work);
      while (!work.isEmpty()) {
        Object[] next = work.pop();
        MethodNode method = (MethodNode) next[1];
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
          followed(insn, reach, work);
        }
      }
      return reach;
    }

    private void followed(AbstractInsnNode insn, Reach reach, Deque<Object[]> work) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode call = (MethodInsnNode) insn;
        if (!classes.containsKey(call.owner)) {
          reach.opaque |= reflective(call.owner, call.name);
          return;
        }
        if (call.getOpcode() == Opcodes.INVOKESTATIC) {
          initialiser(call.owner, reach, work);
        }
        declared(call.owner, call.name, call.desc, reach, work);
        if (call.getOpcode() == Opcodes.INVOKEVIRTUAL || call.getOpcode() == Opcodes.INVOKEINTERFACE) {
          for (String sub : below(call.owner)) {
            add(sub, classes.get(sub), call.name, call.desc, reach, work);
          }
        }
      } else if (insn instanceof FieldInsnNode) {
        FieldInsnNode field = (FieldInsnNode) insn;
        if ((field.getOpcode() == Opcodes.GETSTATIC || field.getOpcode() == Opcodes.PUTSTATIC)
            && classes.containsKey(field.owner)) {
          initialiser(field.owner, reach, work);
        }
      } else if (insn instanceof TypeInsnNode) {
        TypeInsnNode type = (TypeInsnNode) insn;
        if (type.getOpcode() == Opcodes.NEW && classes.containsKey(type.desc)) {
          callbacks(type.desc, reach, work);
        }
      } else if (insn instanceof InvokeDynamicInsnNode) {
        for (Object arg : ((InvokeDynamicInsnNode) insn).bsmArgs) {
          if (arg instanceof Handle && classes.containsKey(((Handle) arg).getOwner())) {
            Handle handle = (Handle) arg;
            if (handle.getTag() == Opcodes.H_NEWINVOKESPECIAL) {
              callbacks(handle.getOwner(), reach, work);
              declared(handle.getOwner(), handle.getName(), handle.getDesc(), reach, work);
            } else {
              initialiser(handle.getOwner(), reach, work);
              declared(handle.getOwner(), handle.getName(), handle.getDesc(), reach, work);
            }
          }
        }
      } else if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof Type) {
        // A class named by its literal may be made, or have its constants listed, by a
        // library that is handed it: an `EnumMap` lists an enum's.
        Type type = (Type) ((LdcInsnNode) insn).cst;
        if (type.getSort() == Type.OBJECT && classes.containsKey(type.getInternalName())) {
          everything(type.getInternalName(), reach, work);
        }
      }
    }

    // A class being initialised has its supertypes initialised first.
    private void initialiser(String name, Reach reach, Deque<Object[]> work) {
      for (String type : above(name)) {
        add(type, classes.get(type), "<clinit>", "()V", reach, work);
      }
    }

    // What code outside the project may call on an object of the project: the methods of the
    // class and its supertypes in the project that implement what a supertype outside it
    // declares, `equals` and `compare` and `run`. Outside code can only name those. A supertype
    // outside the project and the JDK cannot be read here, and then any method may be one.
    private void callbacks(String name, Reach reach, Deque<Object[]> work) {
      initialiser(name, reach, work);
      Set<String> named = foreign(name);
      for (String type : above(name)) {
        for (MethodNode method : classes.get(type).methods) {
          boolean instance = (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0;
          if (instance
              && !method.name.equals("<init>")
              && (named == null || named.contains(method.name + method.desc))) {
            add(type, classes.get(type), method.name, method.desc, reach, work);
          }
        }
      }
    }

    // What the supertypes of a class outside the project declare, by name and descriptor; null
    // when one of them cannot be read.
    private Set<String> foreign(String name) {
      Set<String> out = new HashSet<>();
      Deque<String> todo = new ArrayDeque<>();
      Set<String> done = new HashSet<>();
      todo.add(name);
      while (!todo.isEmpty()) {
        String type = todo.pop();
        if (!done.add(type)) {
          continue;
        }
        ClassNode node = classes.get(type);
        if (node == null) {
          node = jdk(type);
          if (node == null) {
            return null;
          }
          for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0) {
              out.add(method.name + method.desc);
            }
          }
        }
        if (node.superName != null) {
          todo.add(node.superName);
        }
        todo.addAll(node.interfaces);
      }
      return out;
    }

    // A type of the JDK this runs on, which is the project's own.
    private final Map<String, ClassNode> jdk = new HashMap<>();

    private ClassNode jdk(String name) {
      if (jdk.containsKey(name)) {
        return jdk.get(name);
      }
      ClassNode node = null;
      try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
        if (in != null && (name.startsWith("java/") || name.startsWith("javax/"))) {
          node = new ClassNode();
          new ClassReader(in).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        }
      } catch (IOException e) {
        node = null;
      }
      jdk.put(name, node);
      return node;
    }

    // Every method of a class and of its supertypes in the project.
    private void everything(String name, Reach reach, Deque<Object[]> work) {
      initialiser(name, reach, work);
      for (String type : above(name)) {
        for (MethodNode method : classes.get(type).methods) {
          add(type, classes.get(type), method.name, method.desc, reach, work);
        }
      }
    }

    // The method a call names, where it is declared: in the class or above it.
    private void declared(String owner, String name, String desc, Reach reach, Deque<Object[]> work) {
      for (String type : above(owner)) {
        add(type, classes.get(type), name, desc, reach, work);
      }
    }

    private void add(
        String owner, ClassNode node, String name, String desc, Reach reach, Deque<Object[]> work) {
      if (node == null) {
        return;
      }
      for (MethodNode method : node.methods) {
        if (method.name.equals(name) && method.desc.equals(desc)) {
          if (reach.methods.add(owner + "." + name + desc)) {
            work.push(new Object[] {owner, method});
          }
          return;
        }
      }
    }

    // The class and its supertypes, as far as the project has them.
    private List<String> above(String name) {
      List<String> out = new ArrayList<>();
      Deque<String> todo = new ArrayDeque<>();
      todo.add(name);
      while (!todo.isEmpty()) {
        String type = todo.pop();
        ClassNode node = classes.get(type);
        if (node == null || out.contains(type)) {
          continue;
        }
        out.add(type);
        if (node.superName != null) {
          todo.add(node.superName);
        }
        todo.addAll(node.interfaces);
      }
      return out;
    }

    // Every class of the project below this one, which may override what it declares.
    private List<String> below(String name) {
      List<String> out = new ArrayList<>();
      Deque<String> todo = new ArrayDeque<>(subtypes.getOrDefault(name, new ArrayList<>()));
      while (!todo.isEmpty()) {
        String type = todo.pop();
        if (!out.contains(type)) {
          out.add(type);
          todo.addAll(subtypes.getOrDefault(type, new ArrayList<>()));
        }
      }
      return out;
    }

    // Code reached through a name rather than a call: a class named in a string, a method
    // looked up, a service loaded. Where it leads is not in the bytecode.
    private static boolean reflective(String owner, String name) {
      if (owner.startsWith("java/lang/reflect/") || owner.startsWith("java/lang/invoke/")) {
        return true;
      }
      if (owner.equals("java/util/ServiceLoader")) {
        return true;
      }
      if (owner.equals("java/lang/ClassLoader")) {
        return name.startsWith("loadClass");
      }
      if (owner.equals("java/lang/Class")) {
        return name.equals("forName")
            || name.equals("newInstance")
            || name.equals("getEnumConstants")
            || name.startsWith("getMethod")
            || name.startsWith("getDeclaredMethod")
            || name.startsWith("getConstructor")
            || name.startsWith("getDeclaredConstructor");
      }
      return false;
    }
  }
}
