package io.github.lnds.kalku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceTest {
  private static void at(Source src, int index, int line, int col, int at) {
    Source.Position p = src.position(index);
    assertEquals(line + ":" + col + "@" + at, p.line + ":" + p.col + "@" + p.at);
  }

  @Test
  void aColumnCountsCharactersAndAnOffsetCountsBytes() {
    // `ñ` is one unit and two bytes; `😀` is two units, one character and four bytes.
    Source src = new Source("ñ😀x\ny");
    at(src, 0, 1, 1, 0);
    at(src, 1, 1, 2, 2);
    at(src, 3, 1, 3, 6);
    at(src, 4, 1, 4, 7);
    at(src, 5, 2, 1, 8);
    at(src, 6, 2, 2, 9);
  }

  @Test
  void aLineEndsTheWayTheCompilerCountsIt() {
    Source src = new Source("a\r\nb\rc\nd");
    at(src, 3, 2, 1, 3);
    at(src, 5, 3, 1, 5);
    at(src, 7, 4, 1, 7);
  }

  @Test
  void aSpliceReplacesExactlyTheSpan() {
    assertEquals("a != b", new Source("a == b").splice(2, 4, "!="));
  }

  @Test
  void bytesThatAreNotUtf8AreRefusedNotGuessed(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("Latin.java");
    Files.write(file, "class A { String s = \"ni\u00f1o\"; }".getBytes(StandardCharsets.ISO_8859_1));
    assertThrows(IOException.class, () -> Source.read(file));
  }
}
