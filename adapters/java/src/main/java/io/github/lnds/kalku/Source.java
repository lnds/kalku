package io.github.lnds.kalku;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Positions in a source file, in the three ways they are counted.
 *
 * <p>The compiler reports an offset in UTF-16 units. The protocol wants a line (1-based), a
 * column (1-based, in characters) and a byte offset (0-based) in the file as it is on disk.
 * This is the one place those meet, so a site cannot disagree with itself about where it is.
 */
final class Source {
  /** A position as the protocol states it. */
  static final class Position {
    final int line;
    final int col;
    final int at;

    Position(int line, int col, int at) {
      this.line = line;
      this.col = col;
      this.at = at;
    }
  }

  final String text;
  final byte[] data;
  // Where each line begins, and the byte each UTF-16 unit begins at.
  private final int[] lineStarts;
  private final int[] byteAt;

  Source(String text) {
    this.text = text;
    this.data = text.getBytes(StandardCharsets.UTF_8);
    this.lineStarts = lineStarts(text);
    this.byteAt = byteOffsets(text);
  }

  /** Reads a file as UTF-8, and refuses bytes that are not: a guess would move every offset. */
  static Source read(Path path) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    String text =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    return new Source(text);
  }

  // A line ends at `\n`, at `\r\n`, or at a `\r` alone, as the compiler counts them.
  private static int[] lineStarts(String text) {
    List<Integer> starts = new ArrayList<>();
    starts.add(0);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n') {
        starts.add(i + 1);
      } else if (c == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n')) {
        starts.add(i + 1);
      }
    }
    int[] out = new int[starts.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = starts.get(i);
    }
    return out;
  }

  private static int[] byteOffsets(String text) {
    int[] out = new int[text.length() + 1];
    int at = 0;
    int i = 0;
    while (i < text.length()) {
      int point = text.codePointAt(i);
      int units = Character.charCount(point);
      for (int u = 0; u < units; u++) {
        out[i + u] = at;
      }
      at += point < 0x80 ? 1 : point < 0x800 ? 2 : point < 0x10000 ? 3 : 4;
      i += units;
    }
    out[text.length()] = at;
    return out;
  }

  /** The protocol's position for an offset the compiler gave. */
  Position position(int index) {
    int lo = 0;
    int hi = lineStarts.length - 1;
    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1;
      if (lineStarts[mid] <= index) {
        lo = mid;
      } else {
        hi = mid - 1;
      }
    }
    int col = text.codePointCount(lineStarts[lo], index) + 1;
    return new Position(lo + 1, col, byteAt[index]);
  }

  String slice(int start, int end) {
    return text.substring(start, end);
  }

  /** The text with {@code start..end} replaced. */
  String splice(int start, int end, String replacement) {
    return text.substring(0, start) + replacement + text.substring(end);
  }
}
