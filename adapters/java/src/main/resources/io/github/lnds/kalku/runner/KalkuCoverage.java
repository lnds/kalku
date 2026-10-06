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
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ISourceFileCoverage;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;

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
 * code — a constant, which it copies into the classes that use it — or where the code ran
 * outside any one test, every test is named: a place wrongly left without tests would never be
 * cast and would drop out of the measure, and a place with too many only costs time.
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

    // Which lines have code at all, from the classes alone.
    Map<String, BitSet> coded = new HashMap<>();
    for (String directory : directories) {
      CoverageBuilder shape = new CoverageBuilder();
      new Analyzer(new ExecutionDataStore(), shape).analyzeAll(new File(directory));
      lines(shape, false, directory, coded);
    }
    for (Place place : inOrder) {
      BitSet code = coded.get(place.source);
      place.all = code == null || code.get(place.from, place.to + 1).isEmpty();
    }

    // What ran outside any one test: when the classes were first used, and between tests.
    for (String shared : new String[] {"init.exec", "outside.exec"}) {
      Path file = dumps.resolve(shared);
      if (Files.isRegularFile(file)) {
        for (Map.Entry<String, BitSet> reached : reached(file, directories).entrySet()) {
          for (Place place : places.getOrDefault(reached.getKey(), new ArrayList<>())) {
            if (!reached.getValue().get(place.from, place.to + 1).isEmpty()) {
              place.all = true;
              place.counted = true;
            }
          }
        }
      }
    }

    // What ran while each test did. A method run many times is one test.
    for (String line : Files.readAllLines(Paths.get(args[2]), StandardCharsets.UTF_8)) {
      String[] part = line.split("\t", 2);
      if (part.length < 2 || part[1].isEmpty()) {
        continue;
      }
      for (Map.Entry<String, BitSet> reached : reached(dumps.resolve(part[0]), directories).entrySet()) {
        for (Place place : places.getOrDefault(reached.getKey(), new ArrayList<>())) {
          if (!reached.getValue().get(place.from, place.to + 1).isEmpty()) {
            place.counted = true;
            if (!place.all) {
              place.tests.add(part[1]);
            }
          }
        }
      }
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

  // The lines of each source file that ran in what one file of counts holds.
  private static Map<String, BitSet> reached(Path counted, Set<String> directories)
      throws IOException {
    ExecFileLoader loader = new ExecFileLoader();
    loader.load(counted.toFile());
    ExecutionDataStore store = loader.getExecutionDataStore();
    Map<String, BitSet> out = new HashMap<>();
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
      lines(builder, true, directory, out);
    }
    return out;
  }

  // By class directory and source file, `dir|a/b/C.java`: the lines that ran, or the lines
  // that have code.
  private static void lines(
      CoverageBuilder builder, boolean ran, String directory, Map<String, BitSet> out) {
    for (ISourceFileCoverage source : builder.getSourceFiles()) {
      String name =
          source.getPackageName().isEmpty()
              ? source.getName()
              : source.getPackageName() + "/" + source.getName();
      BitSet lines = out.computeIfAbsent(directory + "|" + name, n -> new BitSet());
      for (int line = source.getFirstLine(); line >= 0 && line <= source.getLastLine(); line++) {
        int status = source.getLine(line).getStatus();
        if (ran
            ? status == ICounter.FULLY_COVERED || status == ICounter.PARTLY_COVERED
            : status != ICounter.EMPTY) {
          lines.set(line);
        }
      }
    }
  }
}
