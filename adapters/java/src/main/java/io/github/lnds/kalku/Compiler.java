package io.github.lnds.kalku;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiling, with the project's own compiler and inside this process.
 *
 * <p>A wekufe is never written among the sources: the one file it changes is handed to the
 * compiler from memory, and the classes go to a directory of the cast's own.
 */
final class Compiler {
  private Compiler() {}

  /** The first thing the compiler objected to, or nothing. */
  static final class Result {
    final String error;

    Result(String error) {
      this.error = error;
    }

    boolean ok() {
      return error == null;
    }
  }

  private static final class Changed extends SimpleJavaFileObject {
    private final String text;

    Changed(URI where, String text) {
      super(where, Kind.SOURCE);
      this.text = text;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return text;
    }
  }

  private static final Writer NOWHERE =
      new Writer() {
        @Override
        public void write(char[] buffer, int from, int length) {}

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  /**
   * Compiles these files into {@code out}, one of them read from {@code text} instead of from
   * disk when {@code changed} names it.
   *
   * <p>Only what is listed is compiled. Anything else the files refer to is taken, already
   * compiled, from the class path.
   */
  static Result compile(
      List<Path> files,
      Path changed,
      String text,
      List<Path> classpath,
      Path out,
      List<String> flags,
      Path root)
      throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    Files.createDirectories(out);
    List<String> options = new ArrayList<>(flags);
    options.add("-d");
    options.add(out.toString());
    options.add("-classpath");
    options.add(classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
    // No source is looked for beyond the ones listed, and no warning is what is asked about.
    options.add("-sourcepath");
    options.add("");
    options.add("-implicit:none");
    options.add("-nowarn");

    DiagnosticCollector<JavaFileObject> said = new DiagnosticCollector<>();
    try (StandardJavaFileManager manager =
        compiler.getStandardFileManager(said, Locale.ROOT, StandardCharsets.UTF_8)) {
      List<JavaFileObject> units = new ArrayList<>();
      boolean replaced = changed == null;
      for (Path file : files) {
        if (file.equals(changed)) {
          units.add(new Changed(file.toUri(), text));
          replaced = true;
        } else {
          for (JavaFileObject unit : manager.getJavaFileObjects(file.toFile())) {
            units.add(unit);
          }
        }
      }
      // A wekufe that was never handed to the compiler would be the original, measured.
      if (!replaced) {
        return new Result(changed + " is not among the sources being compiled");
      }
      boolean ok;
      try {
        ok = compiler.getTask(NOWHERE, manager, said, options, null, units).call();
      } catch (RuntimeException e) {
        return new Result("the compiler failed: " + e);
      }
      for (Diagnostic<? extends JavaFileObject> d : said.getDiagnostics()) {
        if (d.getKind() == Diagnostic.Kind.ERROR) {
          return new Result(where(d, root) + d.getMessage(Locale.ROOT));
        }
      }
      return ok ? new Result(null) : new Result("the compiler refused the file and said nothing");
    }
  }

  // `src/main/java/a/B.java:12: `, the way a compiler names a place.
  private static String where(Diagnostic<? extends JavaFileObject> d, Path root) {
    if (d.getSource() == null) {
      return "";
    }
    String name = d.getSource().getName();
    try {
      Path file = java.nio.file.Paths.get(d.getSource().toUri());
      if (file.startsWith(root)) {
        name = root.relativize(file).toString();
      }
    } catch (RuntimeException e) {
      // The name the compiler gave will do.
    }
    return name + ":" + d.getLineNumber() + ": ";
  }
}
