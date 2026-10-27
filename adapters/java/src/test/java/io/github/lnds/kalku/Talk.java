package io.github.lnds.kalku;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Talking to a kalku in this process the way the kaikai side does over a pipe: the channel
 * stays open until every request has had its answer. An input that ends while a cast is
 * running means nobody is left to hear its outcome, and the kalku rightly ends the cast.
 */
final class Talk {
  private Talk() {}

  // What a run asks for when its config names no spells: `return` waits to be named.
  static final List<String> DEFAULT_SPELLS =
      Arrays.asList(Spell.ARM, Spell.COMPARE, Spell.CONNECT, Spell.NEGATE, Spell.LITERAL, Spell.CALL);

  private static final class Heard extends ByteArrayOutputStream {
    synchronized int lines() {
      int n = 0;
      for (int i = 0; i < count; i++) {
        if (buf[i] == '\n') {
          n++;
        }
      }
      return n;
    }

    @Override
    public synchronized void flush() {
      notifyAll();
    }
  }

  /**
   * One kalku asked one request at a time, each answered before the next is written: what a
   * test does between two of them, the kalku sees done.
   */
  static final class Conversation implements AutoCloseable {
    private final PipedOutputStream to = new PipedOutputStream();
    private final Heard out = new Heard();
    private final Thread kalku;
    private final Throwable[] failed = {null};
    private int asked;

    Conversation() throws Exception {
      PipedInputStream in = new PipedInputStream(to, 1 << 20);
      kalku =
          new Thread(
              () -> {
                try {
                  new Service(in, out, "test").serve();
                } catch (Throwable t) {
                  failed[0] = t;
                }
                synchronized (out) {
                  out.notifyAll();
                }
              },
              "kalku-under-test");
      kalku.start();
    }

    /** The reply to this request. */
    Map<?, ?> ask(String request) throws Exception {
      asked++;
      to.write((request + "\n").getBytes(StandardCharsets.UTF_8));
      to.flush();
      synchronized (out) {
        long deadline = System.currentTimeMillis() + 600_000;
        while (out.lines() < asked && kalku.isAlive() && System.currentTimeMillis() < deadline) {
          out.wait(200);
        }
      }
      if (failed[0] != null) {
        throw new IllegalStateException("the kalku failed", failed[0]);
      }
      String[] lines = new String(out.toByteArray(), StandardCharsets.UTF_8).split("\n");
      if (lines.length < asked) {
        throw new IllegalStateException("no reply to " + request);
      }
      return (Map<?, ?>) Json.decode(lines[asked - 1]);
    }

    @Override
    public void close() throws java.io.IOException {
      to.close();
      try {
        kalku.join(60_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** What a kalku says, line by line, to these requests. */
  static List<Map<?, ?>> ask(String... requests) throws Exception {
    PipedOutputStream to = new PipedOutputStream();
    PipedInputStream in = new PipedInputStream(to, 1 << 20);
    Heard out = new Heard();
    Throwable[] failed = {null};
    Thread kalku =
        new Thread(
            () -> {
              try {
                new Service(in, out, "test").serve();
              } catch (Throwable t) {
                failed[0] = t;
              }
              synchronized (out) {
                out.notifyAll();
              }
            },
            "kalku-under-test");
    kalku.start();
    to.write((String.join("\n", requests) + "\n").getBytes(StandardCharsets.UTF_8));
    to.flush();
    synchronized (out) {
      long deadline = System.currentTimeMillis() + 600_000;
      while (out.lines() < requests.length && kalku.isAlive() && System.currentTimeMillis() < deadline) {
        out.wait(200);
      }
    }
    to.close();
    kalku.join(60_000);
    if (failed[0] != null) {
      throw new IllegalStateException("the kalku failed", failed[0]);
    }
    List<Map<?, ?>> said = new ArrayList<>();
    for (String line : new String(out.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
      if (!line.isEmpty()) {
        said.add((Map<?, ?>) Json.decode(line));
      }
    }
    return said;
  }
}
