package io.github.lnds.kalku;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Which compiled classes a build takes for its tests.
 *
 * <p>Both build tools decide by the class file, {@code a/b/CTest.class} under the directory the
 * tests are compiled into, held against patterns the build states: {@code **} is any number of
 * directories, {@code *} any part of one name, {@code ?} one character of it. A class is a
 * test when a pattern that includes matches it and none that excludes does.
 *
 * <p>A pattern is honoured exactly or the build is refused by its name. A class left out by a
 * pattern read wrongly is a test that never runs, and what only it notices would be reported
 * as a hole in the suite.
 */
final class Suite {
  // What surefire takes when a build names nothing.
  private static final List<String> SUREFIRE_INCLUDES =
      Arrays.asList("**/Test*.java", "**/*Test.java", "**/*Tests.java", "**/*TestCase.java");
  private static final List<String> SUREFIRE_EXCLUDES = Arrays.asList("**/*$*");

  // Patterns over the path of a class file. No pattern that includes means every class.
  private final List<String> includes;
  private final List<String> excludes;

  private Suite(List<String> includes, List<String> excludes) {
    this.includes = includes;
    this.excludes = excludes;
  }

  /**
   * The classes surefire runs for these {@code includes} and {@code excludes}. Either list,
   * left empty, is surefire's own: the four names of a test class, without nested classes.
   *
   * @param called the module, for whoever reads why it was refused
   * @throws Maven.Failed for a pattern surefire reads in a way this does not
   */
  static Suite surefire(List<String> includes, List<String> excludes, String called)
      throws Maven.Failed {
    List<String> in = new ArrayList<>();
    for (String stated : includes.isEmpty() ? SUREFIRE_INCLUDES : includes) {
      in.addAll(surefire(stated, "includes", called));
    }
    List<String> out = new ArrayList<>();
    for (String stated : excludes.isEmpty() ? SUREFIRE_EXCLUDES : excludes) {
      out.addAll(surefire(stated, "excludes", called));
    }
    return new Suite(in, out);
  }

  /**
   * The classes Gradle's {@code test} task runs for these patterns, which it holds against
   * the class file as they are written. With none that includes, every class is a candidate.
   */
  static Suite gradle(List<String> includes, List<String> excludes) {
    return new Suite(
        includes.stream().map(Suite::ant).collect(Collectors.toList()),
        excludes.stream().map(Suite::ant).collect(Collectors.toList()));
  }

  // One entry of a surefire list as patterns over class files. An entry can hold several,
  // between commas. A class may be named as a source, as a class file or by its qualified
  // name, with or without its package, and is looked for in any directory.
  private static List<String> surefire(String stated, String list, String called)
      throws Maven.Failed {
    List<String> out = new ArrayList<>();
    for (String part : stated.split(",")) {
      String one = part.trim().replace('\\', '/');
      if (one.isEmpty()) {
        continue;
      }
      // A regular expression, a pattern that excludes from inside a list that includes, and
      // a filter on methods: surefire has all three, each with rules of its own.
      if (one.startsWith("%") || one.startsWith("!") || one.contains("#")) {
        throw new Maven.Failed(
            "surefire's `"
                + list
                + "` in "
                + called
                + " has `"
                + one
                + "`, a form of pattern kalku does not read: it would have to guess which "
                + "classes are that build's tests. Patterns over class names and paths "
                + "(`**/*IT.java`, `com.acme.*Check`) are read");
      }
      if (one.endsWith(".java")) {
        one = one.substring(0, one.length() - ".java".length()) + ".class";
      }
      if (one.endsWith(".class")) {
        one = one.substring(0, one.length() - ".class".length()).replace('.', '/') + ".class";
      } else if (!one.contains("/")) {
        one = one.replace('.', '/');
      }
      if (!one.startsWith("**/")) {
        one = "**/" + one;
      }
      out.add(ant(one));
      if (!one.endsWith(".class") && !one.endsWith(".*")) {
        out.add(ant(one + ".class"));
      }
    }
    return out;
  }

  // A pattern that ends at a directory takes everything under it.
  private static String ant(String pattern) {
    String one = pattern.replace('\\', '/');
    return one.endsWith("/") ? one + "**" : one;
  }

  /** The names of the classes under this directory that the build takes for tests, sorted. */
  List<String> classes(Path directory) throws IOException {
    List<String> out = new ArrayList<>();
    if (!Files.isDirectory(directory)) {
      return out;
    }
    List<Path> files;
    try (Stream<Path> all = Files.walk(directory)) {
      files =
          all.filter(p -> p.toString().endsWith(".class") && Files.isRegularFile(p))
              .sorted()
              .collect(Collectors.toList());
    }
    for (Path file : files) {
      String path = directory.relativize(file).toString().replace('\\', '/');
      if (takes(path)) {
        out.add(path.substring(0, path.length() - ".class".length()).replace('/', '.'));
      }
    }
    java.util.Collections.sort(out);
    return out;
  }

  /** True when the class file at this path, {@code a/b/CTest.class}, is a test's. */
  boolean takes(String path) {
    return (includes.isEmpty() || includes.stream().anyMatch(p -> matches(p, path)))
        && excludes.stream().noneMatch(p -> matches(p, path));
  }

  static boolean matches(String pattern, String path) {
    return matches(pattern.split("/"), 0, path.split("/"), 0);
  }

  private static boolean matches(String[] pattern, int p, String[] path, int at) {
    if (p == pattern.length) {
      return at == path.length;
    }
    if (pattern[p].equals("**")) {
      for (int next = at; next <= path.length; next++) {
        if (matches(pattern, p + 1, path, next)) {
          return true;
        }
      }
      return false;
    }
    return at < path.length
        && name(pattern[p]).matcher(path[at]).matches()
        && matches(pattern, p + 1, path, at + 1);
  }

  private static Pattern name(String pattern) {
    StringBuilder regex = new StringBuilder();
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      regex.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
    }
    return Pattern.compile(regex.toString(), Pattern.DOTALL);
  }
}
