package io.github.lnds.kalku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {
  @Test
  void anObjectKeepsTheOrderItWasBuiltIn() {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("b", 1L);
    o.put("a", Arrays.asList(true, false, null));
    assertEquals("{\"b\":1,\"a\":[true,false,null]}", Json.encode(o));
  }

  @Test
  void onlyQuotesBackslashesAndControlCharactersAreEscaped() {
    assertEquals("\"ñandú \\\"x\\\" \\\\ / 😀\"", Json.encode("ñandú \"x\" \\ / 😀"));
    String controls = new String(new char[] {'\b', '\t', '\n', '\f', '\r', 1, 31});
    assertEquals("\"\\b\\t\\n\\f\\r\\u0001\\u001F\"", Json.encode(controls));
  }

  @Test
  void aRealIsNeverWritten() {
    assertThrows(IllegalArgumentException.class, () -> Json.encode(1.5));
  }

  @Test
  void everyKindOfValueWithWhitespaceBetweenTokens() throws Exception {
    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("a", Arrays.asList(1L, -2.5, 300.0, 0.1, true, false, null));
    expected.put("b", Collections.emptyMap());
    expected.put("c", Collections.emptyList());
    assertEquals(
        expected,
        Json.decode(" {\"a\" : [1, -2.5, 3e2, 1E-1, true, false, null], \"b\": {}, \"c\": []} "));
  }

  @Test
  void anIntegerPastALongIsStillAnInteger() throws Exception {
    assertEquals(Long.MAX_VALUE, Json.decode("9223372036854775807"));
    assertEquals(new BigInteger("9223372036854775808"), Json.decode("9223372036854775808"));
  }

  @Test
  void escapesIncludingACodePointWrittenAsASurrogatePair() throws Exception {
    String escaped = "\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u00f1\\ud83d\\ude00\"";
    assertEquals("\"\\/\b\f\n\r\tñ😀", Json.decode(escaped));
  }

  @Test
  void rawUtf8ComesThroughUntouched() throws Exception {
    assertEquals(Arrays.asList("ñandú", "¡olé!", "😀"), Json.decode("[\"ñandú\", \"¡olé!\", \"😀\"]"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "{",
        "{\"a\"}",
        "{\"a\":1,}",
        "{a:1}",
        "[1,]",
        "[1 2]",
        "01",
        "1.",
        ".5",
        "-",
        "1e",
        "1e999",
        "tru",
        "nil",
        "\"open",
        "\"\\x\"",
        "\"\\u12\"",
        "\"\\ud83d\"",
        "\"\\ude00\"",
        "\"\\ud83dx\"",
        "{} {}",
        "\"a\nb\""
      })
  void refuses(String bad) {
    assertThrows(Json.Invalid.class, () -> Json.decode(bad));
  }

  @Test
  void aDocumentNestedWithoutEndIsRefusedNotFollowed() {
    StringBuilder deep = new StringBuilder();
    for (int i = 0; i < 100_000; i++) {
      deep.append('[');
    }
    assertThrows(Json.Invalid.class, () -> Json.decode(deep.toString()));
  }

  @Test
  void whatIsEncodedDecodesToTheSameValue() throws Exception {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("text", "línea 1\nlínea \"2\"\t\\ " + (char) 0 + " 😀");
    value.put("numbers", Arrays.asList(0L, -1L, 9_007_199_254_740_993L));
    value.put("nested", Arrays.asList(Collections.singletonMap("a", null), true, false));
    assertEquals(value, Json.decode(Json.encode(value)));
  }
}
