package io.github.lnds.kalku;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Newline-delimited framing with a ceiling.
 *
 * <p>A line longer than the limit is discarded up to the next newline without being held, and
 * the next line is read as if nothing had happened: a peer that sends gigabytes without a
 * newline must not be able to make this process keep them, and one oversized message must not
 * cost the ones after it.
 *
 * <p>Lines are bytes until they are whole, and then UTF-8 whatever the platform's own charset
 * is: the protocol says so, and the JDK's default has not been the same on every release.
 */
final class Framing {
  private Framing() {}

  /** What a line longer than the limit is read as. The one instance, compared by identity. */
  @SuppressWarnings("StringOperationCanBeSimplified")
  static final String TOO_LONG = new String("line_too_long");

  // Bytes that are not UTF-8 are not JSON either; this is text no decoder accepts.
  private static final String NOT_TEXT = "\u0000";

  /** The next line, {@link #TOO_LONG}, or {@code null} at the end of input. */
  static String readLine(InputStream in, int limit) throws IOException {
    ByteArrayOutputStream held = new ByteArrayOutputStream();
    boolean tooLong = false;
    boolean any = false;
    while (true) {
      int b = in.read();
      if (b < 0) {
        return any ? finish(held, tooLong) : null;
      }
      any = true;
      if (b == '\n') {
        return finish(held, tooLong);
      }
      if (!tooLong && held.size() < limit) {
        held.write(b);
      } else {
        tooLong = true;
        held.reset();
      }
    }
  }

  private static String finish(ByteArrayOutputStream held, boolean tooLong) {
    if (tooLong) {
      return TOO_LONG;
    }
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(held.toByteArray()))
              .toString();
      return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    } catch (CharacterCodingException e) {
      return NOT_TEXT;
    }
  }
}
