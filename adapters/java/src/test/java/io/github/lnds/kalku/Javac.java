package io.github.lnds.kalku;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Compiles one source in memory and says what errors it has, one per line. */
final class Javac {
  private Javac() {}

  static String errors(String source, Path out) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> said = new DiagnosticCollector<>();
    JavaFileObject file =
        new SimpleJavaFileObject(URI.create("string:///Wekufe.java"), JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
          }
        };
    compiler
        .getTask(
            null,
            null,
            said,
            Arrays.asList("-proc:none", "-d", out.toString()),
            null,
            Collections.singletonList(file))
        .call();
    return said.getDiagnostics().stream()
        .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
        .map(d -> d.getLineNumber() + ": " + d.getMessage(null))
        .collect(Collectors.joining("\n"));
  }
}
