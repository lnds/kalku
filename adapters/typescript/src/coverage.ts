// Which tests ran which lines. Every source file is instrumented with the
// parser: a counter before each statement, and in the body of each arrow
// function that is an expression and each class field's initializer. A
// line belongs to the innermost counted node that holds it, so a test that
// reached a node is credited with the lines no node inside it claims.
//
// The instrumented text is served to the runner the way a wekufe is, in
// memory, and a setup file of the kalku's own credits each count to the
// tests running when it happened. What ran outside any test — a module
// loading, a `beforeAll` — is credited to every test of its file: crediting
// a test with too much costs a cast time, crediting it with too little
// makes a survivor of a wekufe no test was asked about.

import { mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";
import ts from "typescript";
import type { CoverageEntry } from "./protocol";
import { scriptKind } from "./sites";

/** What the instrumentation of a project left in the reni. */
export interface Instrumented {
  /** Absolute path of each instrumented file, to the file holding its instrumented text. */
  manifest: Record<string, string>;
  /** For each counter, the file it is in, relative to the root, and the lines it owns. */
  counters: [string, number[]][];
}

// Directories that are not the project's code to measure.
const SKIPPED_DIRS = new Set(["node_modules", "dist", "build", "coverage", "out", ".next", ".turbo", ".cache"]);

/** The project's own code files: what a test can load and a wekufe can be cast in. */
export function sources(root: string, isTest: (rel: string) => boolean, isConfig: (rel: string) => boolean): string[] {
  const out: string[] = [];
  const walk = (dir: string, rel: string): void => {
    for (const name of readdirSync(dir).sort()) {
      if (name.startsWith(".")) continue;
      const abs = path.join(dir, name);
      const r = rel === "" ? name : `${rel}/${name}`;
      const st = statSync(abs);
      if (st.isDirectory()) {
        if (!SKIPPED_DIRS.has(name)) walk(abs, r);
      } else if (scriptKind(r) !== undefined && !/\.d\.[mc]?ts$/.test(r) && !isTest(r) && !isConfig(r)) {
        out.push(r);
      }
    }
  };
  walk(root, "");
  return out;
}

/** Writes the instrumented text of every source into the reni, and says where. */
export function instrumentAll(root: string, dir: string, files: string[]): Instrumented {
  mkdirSync(dir, { recursive: true });
  const result: Instrumented = { manifest: {}, counters: [] };
  files.forEach((rel, i) => {
    const kind = scriptKind(rel)!;
    const text = readFileSync(path.join(root, rel), "utf8");
    const done = instrument(rel, text, kind, result.counters.length);
    if (done === undefined) return;
    const out = path.join(dir, `${i}${path.extname(rel)}`);
    writeFileSync(out, done.text);
    result.manifest[path.join(root, rel)] = out;
    for (const lines of done.owned) result.counters.push([rel, lines]);
  });
  return result;
}

/**
 * The call a counter is. In TypeScript the global is reached through `any`:
 * a runner that checks types, ts-jest among them, would refuse a file that
 * names a property `globalThis` does not declare.
 */
export function counter(kind: ts.ScriptKind): (n: number) => string {
  const typed = kind === ts.ScriptKind.TS || kind === ts.ScriptKind.TSX;
  return typed ? (n) => `(globalThis as any).__kalku?.h(${n})` : (n) => `globalThis.__kalku?.h(${n})`;
}

/**
 * One file's text with counters in it, and the lines each counter owns, or
 * undefined for a file the parser cannot read, which is then not counted.
 *
 * A function's counter is the first thing in its body, after any directive,
 * and owns its header, so a test that called it is credited with the
 * parameters' defaults. Nothing is put before a function's declaration,
 * where it would part an overload from its implementation, nor before
 * `super(...)` in a derived class's constructor.
 */
export function instrument(
  file: string,
  text: string,
  kind: ts.ScriptKind,
  first: number,
): { text: string; owned: number[][] } | undefined {
  const sf = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, kind);
  if ((sf as unknown as { parseDiagnostics: readonly unknown[] }).parseDiagnostics.length > 0) return undefined;
  const COUNT = counter(kind);
  const spans: { from: number; to: number }[] = [];
  const edits: { at: number; text: string }[] = [];
  const count = (from: number, to: number): number => {
    spans.push({ from, to });
    return first + spans.length - 1;
  };
  const statements = (list: ts.NodeArray<ts.Statement>, from = 0): void => {
    for (const s of list.slice(from)) {
      if (counted(s)) edits.push({ at: s.getStart(sf), text: `;${COUNT(count(s.getStart(sf), s.getEnd()))};` });
    }
  };
  const visit = (node: ts.Node): void => {
    if (ambient(node)) return;
    if (ts.isSourceFile(node) || ts.isModuleBlock(node)) statements(node.statements, prologue(node.statements));
    else if (ts.isBlock(node)) statements(node.statements, opening(node));
    else if (ts.isCaseClause(node) || ts.isDefaultClause(node)) statements(node.statements);
    if (isFunction(node) && node.body !== undefined) {
      const header = count(node.getStart(sf), node.body.getStart(sf));
      if (ts.isBlock(node.body)) {
        const at = opening(node.body);
        const after = at === 0 ? node.body.getStart(sf) + 1 : node.body.statements[at - 1]!.getEnd();
        edits.push({ at: after, text: `;${COUNT(header)};` });
      } else {
        edits.push({ at: node.body.getStart(sf), text: `(${COUNT(header)}, ` });
        edits.push({ at: node.body.getEnd(), text: ")" });
      }
    } else if (ts.isPropertyDeclaration(node) && node.initializer !== undefined) {
      const n = count(node.initializer.getStart(sf), node.initializer.getEnd());
      edits.push({ at: node.initializer.getStart(sf), text: `(${COUNT(n)}, ` });
      edits.push({ at: node.initializer.getEnd(), text: ")" });
    }
    ts.forEachChild(node, visit);
  };
  visit(sf);
  edits.sort((a, b) => b.at - a.at);
  let out = text;
  for (const e of edits) out = out.slice(0, e.at) + e.text + out.slice(e.at);
  return { text: out, owned: owners(sf, spans) };
}

type FunctionLike =
  | ts.FunctionDeclaration
  | ts.FunctionExpression
  | ts.ArrowFunction
  | ts.MethodDeclaration
  | ts.ConstructorDeclaration
  | ts.GetAccessorDeclaration
  | ts.SetAccessorDeclaration;

function isFunction(node: ts.Node): node is FunctionLike {
  return (
    ts.isFunctionDeclaration(node) ||
    ts.isFunctionExpression(node) ||
    ts.isArrowFunction(node) ||
    ts.isMethodDeclaration(node) ||
    ts.isConstructorDeclaration(node) ||
    ts.isGetAccessor(node) ||
    ts.isSetAccessor(node)
  );
}

// How many statements open a list as directives, "use strict" and its kind,
// which must stay first.
function prologue(list: ts.NodeArray<ts.Statement>): number {
  let n = 0;
  while (
    n < list.length &&
    ts.isExpressionStatement(list[n]!) &&
    ts.isStringLiteral((list[n] as ts.ExpressionStatement).expression)
  )
    n++;
  return n;
}

// The first statement of a block a counter may go before: after the
// directives of a function's body, and after `super(...)` in the constructor
// of a class that extends another.
function opening(block: ts.Block): number {
  const parent = block.parent;
  if (!isFunction(parent) || parent.body !== block) return 0;
  const n = prologue(block.statements);
  if (!ts.isConstructorDeclaration(parent)) return n;
  const derived = (parent.parent.heritageClauses ?? []).some((h) => h.token === ts.SyntaxKind.ExtendsKeyword);
  if (!derived) return n;
  const sup = block.statements.findIndex(
    (s) =>
      ts.isExpressionStatement(s) &&
      ts.isCallExpression(s.expression) &&
      s.expression.expression.kind === ts.SyntaxKind.SuperKeyword,
  );
  return sup === -1 ? block.statements.length : sup + 1;
}

// A statement that runs something worth counting: not a type, an import, or
// a function's declaration, which runs nothing where it stands.
function counted(s: ts.Statement): boolean {
  return !(
    ts.isInterfaceDeclaration(s) ||
    ts.isTypeAliasDeclaration(s) ||
    ts.isImportDeclaration(s) ||
    ts.isImportEqualsDeclaration(s) ||
    ts.isExportDeclaration(s) ||
    ts.isFunctionDeclaration(s) ||
    ts.isEmptyStatement(s) ||
    ambient(s)
  );
}

function ambient(node: ts.Node): boolean {
  if (!ts.canHaveModifiers(node)) return false;
  return (ts.getModifiers(node) ?? []).some((m) => m.kind === ts.SyntaxKind.DeclareKeyword);
}

// Each line goes to the smallest counted node that holds it.
function owners(sf: ts.SourceFile, spans: { from: number; to: number }[]): number[][] {
  const lineOf = (at: number): number => sf.getLineAndCharacterOfPosition(at).line + 1;
  const owner = new Map<number, number>();
  const bySize = spans.map((s, i) => ({ i, from: lineOf(s.from), to: lineOf(s.to), size: s.to - s.from }));
  bySize.sort((a, b) => b.size - a.size);
  for (const s of bySize) for (let l = s.from; l <= s.to; l++) owner.set(l, s.i);
  const owned: number[][] = spans.map(() => []);
  for (const [line, i] of [...owner.entries()].sort((a, b) => a[0] - b[0])) owned[i]!.push(line);
  return owned;
}

/** One test file's record, as the setup file writes it. */
export interface Counted {
  file: string;
  shared: number[];
  tests: { names: string[] | string; hits: number[] }[];
}

/** Reads every record a coverage run wrote. */
export function records(dir: string): Counted[] {
  const out: Counted[] = [];
  let names: string[];
  try {
    names = readdirSync(dir);
  } catch {
    return out;
  }
  for (const name of names.filter((n) => n.endsWith(".ndjson")).sort()) {
    for (const line of readFileSync(path.join(dir, name), "utf8").split("\n")) {
      if (line.trim() !== "") out.push(JSON.parse(line) as Counted);
    }
  }
  return out;
}

/**
 * The lines each test is credited with: its own counts and its file's
 * shared ones, turned into lines. `idOf` names a test from its file and
 * titles, or says it is not a test the baseline knows.
 */
export function credited(
  counters: [string, number[]][],
  found: Counted[],
  idOf: (file: string, names: string[] | string) => string | undefined,
): Map<string, Map<number, Set<string>>> {
  const byFile = new Map<string, Map<number, Set<string>>>();
  for (const r of found) {
    for (const t of r.tests) {
      const id = idOf(r.file, t.names);
      if (id === undefined) continue;
      for (const n of new Set([...r.shared, ...t.hits])) {
        const counter = counters[n];
        if (counter === undefined) continue;
        const [file, lines] = counter;
        let reached = byFile.get(file);
        if (reached === undefined) byFile.set(file, (reached = new Map()));
        for (const l of lines) {
          let tests = reached.get(l);
          if (tests === undefined) reached.set(l, (tests = new Set()));
          tests.add(id);
        }
      }
    }
  }
  return byFile;
}

/** Coverage entries, one a line, in file and line order. */
export function entries(byFile: Map<string, Map<number, Set<string>>>): CoverageEntry[] {
  const out: CoverageEntry[] = [];
  for (const file of [...byFile.keys()].sort()) {
    const lines = byFile.get(file)!;
    for (const line of [...lines.keys()].sort((a, b) => a - b)) {
      out.push({ file, line, tests: [...lines.get(line)!].sort() });
    }
  }
  return out;
}

/**
 * The packed form: every test id once, and the lines of each file that the
 * same tests reach, together. Written line by line, a function most of a
 * suite calls names most of the suite on each of its lines.
 */
export function packed(all: CoverageEntry[]): string {
  const index = new Map<string, number>();
  const ids: string[] = [];
  const groups = new Map<string, { file: string; lines: number[]; tests: number[] }>();
  for (const e of all) {
    const positions = e.tests.map((t) => {
      let at = index.get(t);
      if (at === undefined) {
        at = ids.length;
        index.set(t, at);
        ids.push(t);
      }
      return at;
    });
    const key = `${e.file}\u0000${positions.join(",")}`;
    let g = groups.get(key);
    if (g === undefined) groups.set(key, (g = { file: e.file, lines: [], tests: positions }));
    g.lines.push(e.line);
  }
  return `${JSON.stringify({ tests: ids, reached: [...groups.values()] })}\n`;
}
