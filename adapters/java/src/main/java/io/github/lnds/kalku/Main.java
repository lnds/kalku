package io.github.lnds.kalku;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** The Java kalku: the protocol loop on standard input and output. */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws IOException {
    // The protocol is bytes on the real descriptors. `System.out` is pointed at stderr, so
    // nothing that prints by habit — a library, the compiler — can write into the channel.
    InputStream in = new BufferedInputStream(new FileInputStream(FileDescriptor.in));
    OutputStream out = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out));
    System.setOut(System.err);
    new Service(in, out, version()).serve();
  }

  // The version is the jar's own, written into its manifest when it is built.
  private static String version() {
    String version = Main.class.getPackage().getImplementationVersion();
    return version == null ? "dev" : version;
  }
}
