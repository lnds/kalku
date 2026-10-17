import assert from "node:assert/strict";
import { test } from "node:test";
import { SPELLS } from "../src/protocol";
import { findSites } from "../src/sites";
import { Source, splice } from "../src/source";

// The parser counts UTF-16 code units; the protocol counts bytes and
// characters. An emoji is two code units, four bytes and one character.
test("a position is in bytes and characters, past letters of every width", () => {
  const text = 'const s = "ñ😀";\nconst n = 1 < 2;\n';
  const src = new Source(text);
  const at = text.indexOf("<");
  assert.deepEqual(src.position(at), { line: 2, col: 13, byte: Buffer.byteLength(text.slice(0, at)) });
  const quote = text.indexOf('"');
  assert.deepEqual(src.position(quote + 4), { line: 1, col: 14, byte: 11 + 2 + 4 });
});

test("a site's bytes are the text it was found in", () => {
  const text = 'export const greet = (n: number) => n >= 1 ? "¡hola, niño! 😀" : "";\n';
  const bytes = Buffer.from(text, "utf8");
  for (const s of findSites("greet.ts", text, SPELLS, [])) {
    assert.equal(bytes.subarray(s.start.byte, s.end!.byte).toString("utf8"), s.original, s.spell);
    const spliced = splice(bytes, s.start.byte, s.end!.byte, s.replacement).toString("utf8");
    assert.ok(spliced.includes(s.replacement));
  }
});
