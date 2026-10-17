// What the tests of one test file run, as one string that changes when any
// of it does. The unit is the test file: what its tests share, a module as
// it loads and a `beforeAll`, runs once for all of them, so a file is run
// whole or not at all.
//
// A test file runs itself and the modules it imports, and the modules
// those import, as TypeScript resolves them with the project's tsconfig.
// Each goes in by its path and what is in it. What is not the project's own
// goes in by package.json, the lockfile, and the versions of Node and the
// runner. What a test can reach without importing it — the tools'
// configuration, the setup files the runner loads, `.env` files, every file
// kept with the suite that is not a test, and the snapshots beside the
// file — goes in for every file.
//
// A dependency no import names — a module loaded through a variable, a
// file read from disk — would be missed. So a file is given its
// fingerprint only when every source its tests were seen to execute is one
// the fingerprint covers; one whose tests reached further is not
// fingerprinted, and always runs.

import { createHash } from "node:crypto";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";
import ts from "typescript";
import { scriptKind } from "./sites";

// What a build or an install leaves, which no test file keeps beside it.
const BUILT = new Set(["node_modules", "dist", "build", "coverage", "out"]);

const LOCKFILES = ["package-lock.json", "npm-shrinkwrap.json", "yarn.lock", "pnpm-lock.yaml", "bun.lock", "bun.lockb"];

/**
 * The titles of every test a file declares, read with the parser, or
 * undefined when they cannot all be known without running it: a title that
 * is not a literal, a table of cases, a test marked `.only`, which changes
 * which of the others run.
 */
export function declaredTests(file: string, text: string): string[][] | undefined {
  const kind = scriptKind(file);
  if (kind === undefined) return undefined;
  const sf = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, kind);
  const found: string[][] = [];
  let known = true;
  const visit = (node: ts.Node, within: string[]): void => {
    if (!known) return;
    if (ts.isCallExpression(node)) {
      const call = testCall(node.expression);
      if (call !== undefined) {
        if (call.dynamic) {
          known = false;
          return;
        }
        const title = node.arguments[0];
        if (title === undefined || !(ts.isStringLiteral(title) || ts.isNoSubstitutionTemplateLiteral(title))) {
          known = false;
          return;
        }
        if (call.skipped) return;
        if (call.kind === "test") found.push([...within, title.text]);
        else node.arguments.slice(1).forEach((a) => visit(a, [...within, title.text]));
        return;
      }
    }
    ts.forEachChild(node, (child) => visit(child, within));
  };
  visit(sf, []);
  return known ? found : undefined;
}

const MODIFIERS = [
  "skip",
  "todo",
  "only",
  "concurrent",
  "sequential",
  "fails",
  "shuffle",
  "each",
  "for",
  "skipIf",
  "runIf",
];

// `it`, `test`, `describe` and what they are written with: `.skip`,
// `.todo`, `.concurrent`, `.only`, `.each`, `x`- and `f`-prefixed.
function testCall(e: ts.Expression): { kind: "test" | "suite"; skipped: boolean; dynamic: boolean } | undefined {
  const chain: string[] = [];
  let at: ts.Expression = e;
  while (ts.isPropertyAccessExpression(at)) {
    chain.unshift(at.name.text);
    at = at.expression;
  }
  if (ts.isCallExpression(at))
    return testCall(at.expression) === undefined ? undefined : { kind: "test", skipped: false, dynamic: true };
  if (!ts.isIdentifier(at)) return undefined;
  const head = at.text;
  const kinds: Record<string, "test" | "suite"> = { it: "test", test: "test", describe: "suite", suite: "suite" };
  const kind = kinds[head] ?? kinds[head.slice(1)];
  if (kind === undefined) return undefined;
  if (!(head in kinds) && head[0] !== "x" && head[0] !== "f") return undefined;
  // `test.extend(...)` and its kind make a new test function; they are not a test.
  if (chain.some((c) => !MODIFIERS.includes(c))) return undefined;
  return {
    kind,
    skipped: head[0] === "x" || chain.includes("skip") || chain.includes("todo"),
    dynamic:
      head[0] === "f" ||
      chain.some((c) => c === "only" || c === "each" || c === "for" || c === "skipIf" || c === "runIf"),
  };
}

/** The project's modules a file imports, transitively, as TypeScript resolves them; paths relative to the root. */
export class Imports {
  private readonly options: ts.CompilerOptions;
  private readonly seen = new Map<string, string[]>();

  constructor(private readonly root: string) {
    this.options = compilerOptions(root);
  }

  closure(file: string): Set<string> {
    const out = new Set<string>();
    const pending = [path.resolve(this.root, file)];
    while (pending.length > 0) {
      const abs = pending.pop()!;
      const rel = path.relative(this.root, abs).split(path.sep).join("/");
      if (out.has(rel) || rel.startsWith("..") || rel.split("/").includes("node_modules")) continue;
      out.add(rel);
      pending.push(...this.direct(abs));
    }
    return out;
  }

  private direct(abs: string): string[] {
    const cached = this.seen.get(abs);
    if (cached !== undefined) return cached;
    let text = "";
    try {
      text = readFileSync(abs, "utf8");
    } catch {
      // A module that cannot be read imports nothing that can be followed.
    }
    const named = scriptKind(abs) === undefined ? [] : ts.preProcessFile(text, true, true).importedFiles;
    const out: string[] = [];
    for (const n of named) {
      const resolved = ts.resolveModuleName(n.fileName, abs, this.options, ts.sys).resolvedModule;
      if (resolved !== undefined && !resolved.isExternalLibraryImport) out.push(resolved.resolvedFileName);
    }
    this.seen.set(abs, out);
    return out;
  }
}

function compilerOptions(root: string): ts.CompilerOptions {
  const fallback: ts.CompilerOptions = {
    allowJs: true,
    resolveJsonModule: true,
    module: ts.ModuleKind.ESNext,
    moduleResolution: ts.ModuleResolutionKind.Bundler,
  };
  const file = ts.findConfigFile(root, ts.sys.fileExists, "tsconfig.json");
  if (file === undefined || path.dirname(file) !== root) return fallback;
  const read = ts.readConfigFile(file, ts.sys.readFile);
  if (read.error !== undefined) return fallback;
  const parsed = ts.parseJsonConfigFileContent(read.config, ts.sys, root);
  return { ...fallback, ...parsed.options, allowJs: true, resolveJsonModule: true };
}

/** What every file's fingerprint covers, whatever it imports. */
export interface Shared {
  /** Paths relative to the root. */
  files: Set<string>;
  /** Everything else that goes in: the versions of what runs the tests. */
  words: string[];
}

/**
 * The inputs every test file shares: the package's manifest and lockfiles,
 * the tools' configuration, `.env` files, the runner's setup files and what
 * they import, and every file kept with the suite that is not a test.
 */
export function shared(
  root: string,
  imports: Imports,
  setups: string[],
  isTest: (rel: string) => boolean,
  isConfig: (rel: string) => boolean,
  suiteDirs: string[],
  words: string[],
): Shared {
  const files = new Set<string>();
  for (const name of readdirSync(root)) {
    const abs = path.join(root, name);
    if (!statSync(abs).isFile()) continue;
    if (
      name === "package.json" ||
      LOCKFILES.includes(name) ||
      /^tsconfig.*\.json$/.test(name) ||
      name.startsWith(".env") ||
      isConfig(name)
    ) {
      files.add(name);
    }
  }
  for (const setup of setups) for (const f of imports.closure(setup)) files.add(f);
  const walk = (dir: string, rel: string, inSuite: boolean): void => {
    for (const name of readdirSync(dir).sort()) {
      if (name.startsWith(".") || BUILT.has(name)) continue;
      const abs = path.join(dir, name);
      const r = rel === "" ? name : `${rel}/${name}`;
      const suite = inSuite || suiteDirs.includes(name);
      if (statSync(abs).isDirectory()) walk(abs, r, suite);
      else if (suite && !isTest(r.replace(/^.*\/(?=[^/]+$)/, ""))) files.add(r);
    }
  };
  walk(root, "", false);
  return { files, words };
}

/** The snapshots kept beside a test file. */
export function snapshots(root: string, testFile: string): string[] {
  const dir = path.join(root, path.dirname(testFile), "__snapshots__");
  if (!existsSync(dir)) return [];
  const base = path.basename(testFile);
  return readdirSync(dir)
    .filter((n) => n.startsWith(base))
    .map((n) => path.join(path.dirname(testFile), "__snapshots__", n).split(path.sep).join("/"));
}

/** One test file's fingerprint, over the shared inputs and the files it covers. */
export function fingerprint(root: string, common: Shared, own: Set<string>): string {
  const h = createHash("sha256");
  for (const w of common.words) h.update(`${w}\u0000`);
  for (const f of [...new Set([...common.files, ...own])].sort()) {
    h.update(`${f}\u0000`);
    try {
      h.update(readFileSync(path.join(root, f)));
    } catch {
      h.update("\u0000missing");
    }
    h.update("\u0000");
  }
  return h.digest("hex").slice(0, 16);
}
