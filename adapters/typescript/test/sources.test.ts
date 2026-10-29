import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { declaredSources } from "../src/sources";
import { search } from "../src/service";

function project(files: Record<string, string>): string {
  const root = mkdtempSync(path.join(tmpdir(), "kalku-ts-sources-"));
  for (const [name, text] of Object.entries(files)) {
    mkdirSync(path.dirname(path.join(root, name)), { recursive: true });
    writeFileSync(path.join(root, name), text);
  }
  return root;
}

function sorted(set: Set<string> | undefined): string[] | undefined {
  return set === undefined ? undefined : [...set].sort();
}

const CODE = "export const one = (n: number) => n + 1;\n";

// A library's benchmarks, examples and scripts are not the library: its
// tsconfig.json says what is.
test("the sources are what the tsconfig.json includes, JavaScript as well", () => {
  const root = project({
    "tsconfig.json": JSON.stringify({ include: ["src/"], exclude: ["src/examples"] }),
    "src/a.ts": CODE,
    "src/b.js": "exports.b = 1;\n",
    "src/examples/e.ts": CODE,
    "benchmarks/bench.ts": CODE,
    "scripts/release.mjs": "",
  });
  try {
    assert.deepEqual(sorted(declaredSources(root)), ["src/a.ts", "src/b.js"]);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

// A solution-style config names no file itself: the projects it references do.
test("a config that only references projects takes their sources", () => {
  const root = project({
    "tsconfig.json": JSON.stringify({ files: [], references: [{ path: "./packages/core" }] }),
    "packages/core/tsconfig.json": JSON.stringify({ compilerOptions: { composite: true }, include: ["src"] }),
    "packages/core/src/x.ts": CODE,
    "packages/core/bench/y.ts": CODE,
  });
  try {
    assert.deepEqual(sorted(declaredSources(root)), ["packages/core/src/x.ts"]);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

// Without a config that names files there is nothing to go by, and nothing is left out.
test("no config, or one that names no file, says nothing", () => {
  const none = project({ "src/a.ts": CODE });
  const empty = project({ "tsconfig.json": JSON.stringify({ include: ["lib"] }), "src/a.ts": CODE });
  try {
    assert.equal(declaredSources(none), undefined);
    assert.equal(declaredSources(empty), undefined);
  } finally {
    rmSync(none, { recursive: true, force: true });
    rmSync(empty, { recursive: true, force: true });
  }
});

test("a file outside the tsconfig.json is left out by design, and one inside is searched", () => {
  const root = project({
    "tsconfig.json": JSON.stringify({ include: ["src"] }),
    "src/a.ts": CODE,
    "examples/e.ts": CODE,
  });
  const asked = { files: [], spells: [], exclude_calls: [] };
  try {
    const outside = search(root, "examples/e.ts", asked);
    assert.equal("reason" in outside && outside.reason, "outside_sources");
    assert.equal("by_design" in outside && outside.by_design, true);
    assert.ok(Array.isArray(search(root, "src/a.ts", asked)));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
