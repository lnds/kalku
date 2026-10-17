import assert from "node:assert/strict";
import { test } from "node:test";
import ts from "typescript";
import { credited, entries, instrument, packed } from "../src/coverage";

// What a type checker says of a file, as ts-jest would ask it: an
// instrumented file must say nothing the original did not.
function diagnostics(name: string, text: string): string[] {
  const options: ts.CompilerOptions = { strict: true, target: ts.ScriptTarget.ES2022, noEmit: true, types: [] };
  const host = ts.createCompilerHost(options);
  const read = host.getSourceFile.bind(host);
  host.getSourceFile = (file, version) =>
    file === name ? ts.createSourceFile(file, text, version, true) : read(file, version);
  const program = ts.createProgram([name], options, host);
  return ts
    .getPreEmitDiagnostics(program)
    .filter((d) => d.file?.fileName === name)
    .map((d) => ts.flattenDiagnosticMessageText(d.messageText, "\n"));
}

const SAMPLE = `"use strict";
export function parse(s: string): number;
export function parse(s: number): number;
export function parse(s: string | number): number {
  return Number(s);
}

class Base {
  constructor(readonly n: number) {}
}

export class Shape extends Base {
  sides = 3;
  constructor(n: number) {
    super(n);
    this.sides = n > 0 ? n : 1;
  }
  get area(): number {
    return this.sides * 2;
  }
}

export const make = (n: number) => ({ n, shape: new Shape(n) });
export const LIMIT = 10;

export namespace Geometry {
  export function origin(): number {
    "use strict";
    return 0;
  }
}

export function route(kind: string): number {
  switch (kind) {
    case "a":
      return 1;
    default:
      return 0;
  }
}
`;

test("an instrumented file parses, keeps its lines, and the type checker says nothing new of it", () => {
  const done = instrument("sample.ts", SAMPLE, ts.ScriptKind.TS, 0)!;
  assert.equal(done.text.split("\n").length, SAMPLE.split("\n").length);
  assert.deepEqual(diagnostics("sample.ts", done.text), diagnostics("sample.ts", SAMPLE));
  assert.deepEqual(diagnostics("sample.ts", SAMPLE), []);
  // A directive stays first, and nothing stands before `super(...)`.
  assert.ok(done.text.startsWith('"use strict";'));
  assert.match(done.text, /super\(n\);;\(globalThis as any\)/);
  assert.doesNotMatch(done.text, /__kalku\?\.h\(\d+\);super/);
});

test("a line is owned by the innermost counted node, and a function's header by the function", () => {
  const done = instrument("sample.ts", SAMPLE, ts.ScriptKind.TS, 0)!;
  const ownerOf = (line: number): number => done.owned.findIndex((lines) => lines.includes(line));
  // `return Number(s)` and the header of `parse` are counted apart.
  assert.notEqual(ownerOf(5), ownerOf(4));
  assert.notEqual(ownerOf(4), -1);
  // `case "a":` belongs to the switch; `return 1` to its own statement.
  assert.notEqual(ownerOf(36), ownerOf(37));
  // Every line that holds code has an owner; a type does not.
  for (const line of [5, 13, 16, 19, 23, 24, 29, 37, 39]) assert.notEqual(ownerOf(line), -1, `line ${line}`);
  assert.equal(ownerOf(2), -1);
});

test("JavaScript is counted without the cast to any, which it cannot parse", () => {
  const done = instrument("plain.js", "const f = (x) => x + 1;\nmodule.exports = f;\n", ts.ScriptKind.JS, 7)!;
  assert.match(done.text, /globalThis\.__kalku\?\.h\(7\)/);
  assert.doesNotMatch(done.text, / as any/);
});

test("a test is credited with its own counts and with what its file ran outside any test", () => {
  const counters: [string, number[]][] = [
    ["src/a.ts", [1]],
    ["src/a.ts", [2, 3]],
    ["src/b.ts", [7]],
  ];
  const byFile = credited(
    counters,
    [
      {
        file: "/r/test/a.test.ts",
        shared: [0],
        tests: [
          { names: ["one"], hits: [1] },
          { names: ["two"], hits: [2] },
        ],
      },
    ],
    (_file, names) => [`test/a.test.ts:${[names].flat().join(" › ")}`],
  );
  assert.deepEqual(entries(byFile), [
    { file: "src/a.ts", line: 1, tests: ["test/a.test.ts:one", "test/a.test.ts:two"] },
    { file: "src/a.ts", line: 2, tests: ["test/a.test.ts:one"] },
    { file: "src/a.ts", line: 3, tests: ["test/a.test.ts:one"] },
    { file: "src/b.ts", line: 7, tests: ["test/a.test.ts:two"] },
  ]);
  const p = JSON.parse(packed(entries(byFile))) as { tests: string[]; reached: unknown[] };
  assert.deepEqual(p.tests, ["test/a.test.ts:one", "test/a.test.ts:two"]);
  assert.deepEqual(p.reached, [
    { file: "src/a.ts", lines: [1], tests: [0, 1] },
    { file: "src/a.ts", lines: [2, 3], tests: [0] },
    { file: "src/b.ts", lines: [7], tests: [1] },
  ]);
});

// A runner names a test by its titles, so two tests that share them cannot
// be told apart; both are credited with what either ran.
test("tests that share their titles are credited with what either ran", () => {
  const byFile = credited(
    [["src/a.ts", [4]]],
    [{ file: "/r/test/a.test.ts", shared: [], tests: [{ names: "same name", hits: [0] }] }],
    () => ["test/a.test.ts:same name", "test/a.test.ts:same name (2)"],
  );
  assert.deepEqual(entries(byFile), [
    { file: "src/a.ts", line: 4, tests: ["test/a.test.ts:same name", "test/a.test.ts:same name (2)"] },
  ]);
});
