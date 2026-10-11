package io.github.lnds.kalku;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.stream.Collectors;

/** The Java kalku: the protocol loop on standard input and output. */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws IOException {
    // The protocol is bytes on the real descriptors. `System.out` is pointed at stderr, so
    // nothing that prints by habit — a library, the compiler — can write into the channel.
    InputStream in = new BufferedInputStream(new FileInputStream(FileDescriptor.in));
    OutputStream out = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out));
    System.setOut(System.err);
    new Service(in, out, version(), Main::leave).serve();
  }

  // The run is gone: everything this JVM started is ended, whoever started it and whatever
  // process group it is in, and then the JVM itself, without finishing what it was doing.
  private static void leave() {
    try {
      long deadline = System.nanoTime() + 2_000_000_000L;
      // Asked again until none is left: what was running may have been starting another.
      while (System.nanoTime() < deadline) {
        List<ProcessHandle> started =
            ProcessHandle.current().descendants().collect(Collectors.toList());
        if (started.isEmpty()) {
          break;
        }
        started.forEach(ProcessHandle::destroyForcibly);
        Thread.sleep(10);
      }
    } catch (InterruptedException | RuntimeException e) {
      // Leaving is what is left to do.
    } finally {
      Runtime.getRuntime().halt(0);
    }
  }

  // The version is the jar's own, written into its manifest when it is built.
  private static String version() {
    String version = Main.class.getPackage().getImplementationVersion();
    return version == null ? "dev" : version;
  }
}
