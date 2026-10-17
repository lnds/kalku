import assert from "node:assert/strict";
import path from "node:path";
import { test } from "node:test";
import { Imports, declaredTests } from "../src/fingerprint";
import { TESTDATA } from "./here";

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
