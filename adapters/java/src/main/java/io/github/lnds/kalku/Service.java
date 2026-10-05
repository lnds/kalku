package io.github.lnds.kalku;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Answering requests.
 *
 * <p>One request per line in, one reply per line out, and nothing else may reach the output:
 * the kaikai side banishes a kalku for a line it cannot read.
 *
 * <p>This kalku finds sites and does not cast yet. What it cannot do it says, in a reply, so a
 * request is never met with silence.
 */
final class Service {
  // Directories whose sources are the suite, by the conventions of Maven and Gradle.
  private static final List<String> TEST_DIRS =
      Arrays.asList("test", "tests", "integrationTest", "testFixtures", "androidTest");

  private final InputStream in;
  private final OutputStream out;
  private final String adapter;
  private Protocol.Request hello;

  Service(InputStream in, OutputStream out, String adapter) {
    this.in = in;
    this.out = out;
    this.adapter = adapter;
  }

  /** Serves until `shutdown` or the end of input. */
  void serve() throws IOException {
    while (true) {
      String line = Framing.readLine(in, Protocol.MAX_LINE);
      if (line == null) {
        return;
      }
      boolean stop = handle(line);
      if (stop) {
        return;
      }
    }
  }

  private void say(String reply) throws IOException {
    out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  // One line in, its reply out, and whether to leave.
  private boolean handle(String line) throws IOException {
    if (line == Framing.TOO_LONG) {
      say(refuse(null, Protocol.LINE_TOO_LONG, "a line longer than " + Protocol.MAX_LINE + " bytes"));
      return false;
    }
    Protocol.Request request;
    try {
      request = Protocol.decode(line);
    } catch (Protocol.DecodeError e) {
      say(refuse(e.id, e.kind, e.detail));
      return false;
    }
    switch (request.type) {
      case "hello":
        say(greet(request));
        return false;
      case "shutdown":
        say(Protocol.bye(request.id));
        return true;
      case "abort":
        // Nothing is ever running here, so there is nothing that was not restored.
        say(Protocol.aborted(request.id, request.cast, true));
        return false;
      default:
        break;
    }
    if (hello == null) {
      say(Protocol.error(request.id, "not_ready", "`hello` has to come first", false));
    } else if (request.type.equals("sites")) {
      say(sites(request));
    } else {
      say(
          Protocol.error(
              request.id,
              "not_implemented",
              "this kalku does not answer `" + request.type + "` yet: it finds sites",
              true));
    }
    return false;
  }

  private static String refuse(Long id, String kind, String detail) {
    return Protocol.error(id == null ? 0 : id, "bad_request", kind + ": " + detail, false);
  }

  // ---- hello ---------------------------------------------------------------

  private String greet(Protocol.Request request) {
    if (request.protocol != Protocol.VERSION) {
      return Protocol.error(
          request.id,
          "protocol_mismatch",
          "kalku speaks protocol " + Protocol.VERSION + ", kaikai side speaks " + request.protocol,
          true);
    }
    if (!compilerPresent()) {
      return Protocol.error(
          request.id,
          "toolchain_missing",
          "this Java runtime ("
              + System.getProperty("java.home")
              + ") has no compiler; kalku reads sources with the project's own javac, so it "
              + "needs a JDK, not a JRE",
          true);
    }
    hello = request;
    return Protocol.ready(request.id, adapter, runtime(), Collections.singletonList("cast"));
  }

  // A runtime cut down to less than a JDK may lack the compiler's classes altogether, and then
  // even asking for it fails to link.
  private static boolean compilerPresent() {
    try {
      return Sites.compilerPresent();
    } catch (LinkageError e) {
      return false;
    }
  }

  private static String runtime() {
    String vendor = System.getProperty("java.vendor", "");
    String version = System.getProperty("java.version", "?");
    return vendor.isEmpty() ? "Java " + version : "Java " + version + " (" + vendor + ")";
  }

  // ---- sites ---------------------------------------------------------------

  private String sites(Protocol.Request request) {
    List<Map<String, Object>> found = new ArrayList<>();
    List<Map<String, Object>> skipped = new ArrayList<>();
    for (String file : request.files) {
      String[] why = search(file, request, found);
      if (why != null) {
        skipped.add(Protocol.skipped(file, why[0], why[1]));
      }
    }
    return Protocol.sitesFound(request.id, found, skipped);
  }

  // The reason and the message a file was skipped with, or null when it was searched.
  private String[] search(String file, Protocol.Request request, List<Map<String, Object>> found) {
    if (!file.endsWith(".java")) {
      return new String[] {"not_java", "`" + file + "` is not a Java source file"};
    }
    if (isTestFile(file)) {
      return new String[] {
        "test_file", "`" + file + "` is part of the suite, which is not measured"
      };
    }
    Source src;
    try {
      Path root = Paths.get(hello.root);
      src = Source.read(root.resolve(file));
    } catch (IOException | InvalidPathException e) {
      return new String[] {"unreadable", "cannot read `" + file + "`: " + e};
    }
    try {
      for (Sites.Site site :
          Sites.find(file, src.text, new HashSet<>(request.spells), request.excludeCalls)) {
        found.add(Protocol.site(site));
      }
      return null;
    } catch (Sites.ParseError e) {
      return new String[] {"parse_error", e.getMessage()};
    }
  }

  /**
   * True when a path is part of the suite, which is the oracle and is never measured: under a
   * test source directory, or named the way a test class is.
   */
  static boolean isTestFile(String relative) {
    String[] parts = relative.replace('\\', '/').split("/");
    for (int i = 0; i < parts.length - 1; i++) {
      if (TEST_DIRS.contains(parts[i])) {
        return true;
      }
    }
    String name = parts[parts.length - 1];
    return name.endsWith("Test.java") || name.endsWith("Tests.java") || name.endsWith("IT.java");
  }
}
