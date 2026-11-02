import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { Imports, declaredTests, fingerprint } from "../src/fingerprint";
import { TESTDATA } from "./here";

test("a fingerprint is the same whether the files it covers were read before or not", () => {
  const root = mkdtempSync(path.join(tmpdir(), "kalku-ts-print-"));
  try {
    writeFileSync(path.join(root, "package-lock.json"), "{}\n");
    writeFileSync(path.join(root, "a.ts"), "export const a = 1;\n");
    writeFileSync(path.join(root, "b.ts"), "export const b = 2;\n");
    const common = { files: new Set(["package-lock.json", "gone.json"]), words: ["node v1", "vitest 3"] };
    // The hash over each word, then each file's path and contents, in path order.
    const expected = (own: string[]): string => {
      const h = createHash("sha256");
      for (const w of common.words) h.update(`${w}\u0000`);
      const contents: Record<string, string> = {
        "a.ts": "export const a = 1;\n",
        "b.ts": "export const b = 2;\n",
        "package-lock.json": "{}\n",
      };
      for (const f of [...new Set([...common.files, ...own])].sort()) {
        h.update(`${f}\u0000`);
        h.update(contents[f] ?? "\u0000missing");
        h.update("\u0000");
      }
      return h.digest("hex").slice(0, 16);
    };
    const read = new Map<string, Buffer | null>();
    assert.equal(fingerprint(root, common, new Set(["a.ts"]), read), expected(["a.ts"]));
    assert.equal(fingerprint(root, common, new Set(["b.ts", "a.ts"]), read), expected(["a.ts", "b.ts"]));
    assert.equal(fingerprint(root, common, new Set(["b.ts"])), expected(["b.ts"]));
    assert.deepEqual([...read.keys()].sort(), ["a.ts", "b.ts", "gone.json", "package-lock.json"]);
    assert.equal(read.get("gone.json"), null);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("the tests a file declares are read with the parser, nested in their suites, skipped ones left out", () => {
  const text = `
import { describe, expect, it, test } from "vitest";
const extended = test.extend({ db: async ({}, use) => use(1) });
describe("parser", () => {
  it("reads a number", () => expect(1).toBe(1));
  describe(\`nested\`, () => {
    test.concurrent("reads a word", () => {});
  });
  it.skip("is not run", () => {});
  xit("is not run either", () => {});
  it.todo("is not written");
});
extended("uses a fixture", ({ db }) => expect(db).toBe(1));
`;
  assert.deepEqual(declaredTests("a.test.ts", text), [
    ["parser", "reads a number"],
    ["parser", "nested", "reads a word"],
  ]);
});

test("a file whose tests cannot all be named without running it is not known", () => {
  assert.equal(declaredTests("a.test.ts", 'it.each([1, 2])("case %d", (n) => {});'), undefined);
  assert.equal(declaredTests("a.test.ts", "it(`case ${n}`, () => {});"), undefined);
  assert.equal(declaredTests("a.test.ts", 'it.only("only this", () => {});'), undefined);
  assert.equal(declaredTests("a.test.ts", 'describe(Parser, () => { it("x", () => {}); });'), undefined);
  assert.equal(declaredTests("a.test.ts", 'test.skipIf(ci)("not on CI", () => {});'), undefined);
});

test("a test file imports its modules and theirs, as TypeScript resolves them, and no package", () => {
  const root = path.join(TESTDATA, "projects", "vitest");
  const closure = [...new Imports(root).closure("test/calc.test.ts")].sort();
  assert.deepEqual(closure, ["src/bounds.ts", "src/calc.ts", "test/calc.test.ts"]);
  assert.deepEqual([...new Imports(root).closure("test/mode.test.ts")].sort(), ["src/mode.ts", "test/mode.test.ts"]);
});
