// The requests, answered one at a time in the order they came.

import { existsSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { credited, entries, instrumentAll, packed, records, sources } from "./coverage";
import { Imports, type Shared, declaredTests, fingerprint, shared, snapshots } from "./fingerprint";
import path from "node:path";
import * as wire from "./protocol";
import type { CastRequest, Hello, Outcome, Request, SitesRequest, Site, Skipped } from "./protocol";
import { Aborted } from "./process";
import { Unsupported, environment, parseId, relative, runnerFor, testId } from "./project";
import type { FileResult, TestResult } from "./results";
import { PrepareError, type Context, type RunResult, type Runner, type Selection, wekufeOf } from "./runner";
import { ParseError, findSites, generated, scriptKind } from "./sites";
import { splice } from "./source";
import { VERSION } from "./version";

/** The oldest Node this kalku runs on, and runs a project's tests on. */
export const OLDEST_NODE = 20;

export type Reply = { line: string; last?: boolean } | { quiet: true };

const say = (line: string): Reply => ({ line });

const OPTIONS = ["runner"];

export class Service {
  private hello: Hello | undefined;
  private runner: Runner | undefined;
  private prepared = false;
  private work = "";

  async handle(r: Request, signal: AbortSignal): Promise<Reply> {
    switch (r.type) {
      case "hello":
        return say(this.greet(r.id, r.hello));
      case "shutdown":
        return { line: wire.bye(r.id), last: true };
      case "abort":
        // The cast was stopped as the abort was heard, and nothing it did
        // outlives it: a wekufe is never written to a file.
        return say(wire.aborted(r.id, r.cast, true));
      case "delegate":
        return say(wire.error(r.id, "bad_request", "this kalku casts its own wekufe and does not delegate", false));
    }
    if (this.hello === undefined || this.runner === undefined) {
      return say(wire.error(r.id, "not_ready", "`hello` has to come first", false));
    }
    switch (r.type) {
      case "sites":
        return say(this.sites(r.id, this.hello.root, r.sites));
      case "prepare":
        return say(await this.prepare(r.id, this.runner, signal));
      case "reset":
        // Every run of the tests is a process of its own, so there is no
        // state held here for a wekufe to dirty.
        return say(wire.resetDone(r.id, true, 0));
      case "reload":
        // Nothing is compiled ahead of a run: the next one reads the files.
        return say(wire.reloaded(r.id, r.files, 0));
    }
    if (!this.prepared) return say(wire.error(r.id, "not_ready", "`prepare` has to come first", false));
    try {
      switch (r.type) {
        case "baseline":
          return say(await this.baseline(r.id, this.hello.root, this.runner, r.reuse, signal));
        case "cast":
          return say(await this.cast(r.id, this.hello.root, this.runner, r.cast, signal));
      }
    } catch (e) {
      if (e instanceof Aborted) return { quiet: true };
      throw e;
    }
  }

  private greet(id: number, given: Hello): string {
    // The kaikai side may name the root as it was given on its command line;
    // the kalku is summoned in the same directory, so it resolves from here.
    // Through any symlink too: Vite reads test files by their real path, and
    // one strict about what it reads finds none of them under a root that
    // names a link.
    const h: Hello = { ...given, root: real(path.resolve(given.root)), reni: path.resolve(given.reni) };
    if (h.protocol !== wire.PROTOCOL) {
      return wire.error(
        id,
        "protocol_mismatch",
        `kalku speaks protocol ${wire.PROTOCOL}, kaikai side speaks ${h.protocol}`,
        true,
      );
    }
    const options = h.options ?? {};
    const unknown = Object.keys(options)
      .filter((k) => !OPTIONS.includes(k))
      .sort();
    if (unknown.length > 0) {
      return wire.error(
        id,
        "bad_options",
        `${unknown.map((k) => `typescript.${k}`).join(", ")}: the TypeScript kalku takes only \`runner\``,
        true,
      );
    }
    const wanted = options["runner"];
    if (wanted !== undefined && wanted !== "vitest" && wanted !== "jest") {
      return wire.error(
        id,
        "bad_options",
        `typescript.runner: expected "vitest" or "jest", got ${JSON.stringify(wanted)}`,
        true,
      );
    }
    const major = Number(process.versions.node.split(".")[0]);
    if (major < OLDEST_NODE) {
      return wire.error(
        id,
        "unsupported_runtime",
        `Node ${process.version} is older than Node ${OLDEST_NODE}, the oldest this kalku runs on`,
        true,
      );
    }
    if (!existsSync(path.join(h.root, "package.json"))) {
      return wire.error(
        id,
        "not_a_node_project",
        `${h.root} has no package.json: the TypeScript kalku measures one package, from its root`,
        true,
      );
    }
    const ctx: Context = {
      root: h.root,
      work: path.join(h.reni, "typescript", String(h.worker)),
      env: environment(h.env),
    };
    this.work = ctx.work;
    try {
      this.runner = runnerFor(ctx, wanted as string | undefined);
    } catch (e) {
      if (e instanceof Unsupported) return wire.error(id, "unsupported_runner", e.message, true);
      throw e;
    }
    this.hello = h;
    const runtime = `Node ${process.versions.node}, ${this.runner.name === "vitest" ? "Vitest" : "Jest"} ${this.runner.version}`;
    return wire.ready(id, VERSION, runtime, [
      "cast",
      "per_test_coverage",
      "abort",
      "cast_after_baseline",
      "partial_baseline",
    ]);
  }

  private async prepare(id: number, runner: Runner, signal: AbortSignal): Promise<string> {
    const started = Date.now();
    try {
      await runner.prepare(signal);
    } catch (e) {
      if (e instanceof PrepareError) return wire.error(id, "prepare_failed", e.message, true);
      throw e;
    }
    this.prepared = true;
    return wire.prepared(id, Date.now() - started, 0);
  }

  // Every test runs. A file that did not run at all is named as a failure
  // of its own, because the tests in it judge nothing.
  // Every test runs, but for the files whose tests the kaikai side offers
  // to reuse with the fingerprint they have now: nothing those tests run has
  // changed since they passed. A file that did not run at all is named as a
  // failure of its own, because the tests in it judge nothing.
  private async baseline(
    id: number,
    root: string,
    runner: Runner,
    reuse: Map<string, string> | undefined,
    signal: AbortSignal,
  ): Promise<string> {
    const started = Date.now();
    const prints = new Prints(root, runner);
    const left = reuse === undefined ? { files: [], tests: [] } : prints.reusable(reuse);
    const ran = await runner.run("all", { skip: left.files }, signal);
    if (ran.failed !== undefined) return wire.error(id, "baseline_failed", ran.failed, true);
    const tests: wire.Timed[] = [];
    const failures: wire.Failure[] = [];
    for (const f of ran.files) {
      const file = relative(root, f.file);
      if (f.broken !== undefined) {
        const test = `${file}:`;
        tests.push({ test, file, duration_ms: 0 });
        failures.push({ test, message: `the file did not run: ${f.broken}` });
      }
      for (const t of unique(f.tests)) {
        if (t.status === "skipped") continue;
        const test = testId(root, f.file, t.names);
        tests.push({ test, file, duration_ms: t.durationMs });
        if (t.status === "failed") failures.push({ test, message: t.failure || "it failed" });
      }
    }
    const passed = new Set(tests.map((t) => t.test));
    for (const f of failures) passed.delete(f.test);
    const counted =
      passed.size === 0
        ? { coverage: { inline: [] }, reached: undefined, doubted: new Set<string>() }
        : await this.coverage(root, runner, ran, passed, left.files, signal);
    if (counted.reached !== undefined) {
      // Taken again now: the run has said which setup files the runner loads.
      const now = new Prints(root, runner);
      const failing = new Set(failures.map((f) => f.test));
      for (const t of tests) {
        const print = now.standing(t.file, counted.reached, failing, counted.doubted, tests);
        if (print !== undefined) t.fingerprint = print;
      }
    }
    return wire.baselineDone(
      id,
      Date.now() - started,
      tests,
      counted.coverage,
      failures,
      [...runner.differences(), ...this.envDifference()],
      left.tests,
    );
  }

  // A second run of the suite, with every source instrumented, says which
  // tests reach which lines. Its own verdicts judge nothing: a test that
  // passed in the first run and not in this one may have stopped before it
  // reached all it reaches, and the coverage is said to be in doubt rather
  // than trusted.
  private async coverage(
    root: string,
    runner: Runner,
    plain: RunResult,
    passed: Set<string>,
    skip: string[],
    signal: AbortSignal,
  ): Promise<{ coverage: wire.Coverage; reached: Map<string, Set<string>> | undefined; doubted: Set<string> }> {
    const dir = path.join(this.work, "coverage");
    rmSync(dir, { recursive: true, force: true });
    const out = path.join(dir, "records");
    mkdirSync(out, { recursive: true });
    const counted = instrumentAll(root, path.join(dir, "instrumented"), sources(root, testFile, configFile));
    const manifest = path.join(dir, "manifest.json");
    writeFileSync(manifest, JSON.stringify(counted.manifest));
    const ran = await runner.run("all", { counting: { manifest, out }, skip }, signal);
    if (ran.failed !== undefined) {
      const withheld = `the run that counts which tests reach which lines failed: ${ran.failed}`;
      return { coverage: { withheld }, reached: undefined, doubted: new Set() };
    }
    const second = new Set<string>();
    for (const f of ran.files)
      for (const t of unique(f.tests)) if (t.status === "passed") second.add(testId(root, f.file, t.names));
    // A counting run in which none of the tests that passed passed again
    // counted nothing: reported as coverage, it would call every line
    // unreached, and the reason would be lost in the reni.
    if (passed.size > 0 && ![...passed].some((t) => second.has(t))) {
      const said = ran.files.find((f) => f.broken !== undefined)?.broken;
      const withheld = `no test that passed passed again in the run that counts which tests reach which lines${said === undefined ? "" : `: ${said}`}`;
      return { coverage: { withheld }, reached: undefined, doubted: new Set() };
    }
    const names = namesByFile(root, plain);
    const found = records(out);
    const reached = new Set<string>();
    const byFile = credited(counted.counters, found, (file, said) => {
      const ids = names.get(relative(root, file))?.get(Array.isArray(said) ? said.join(" ") : said) ?? [];
      const judging = ids.filter((id) => passed.has(id));
      for (const id of judging) reached.add(id);
      return judging;
    });
    const doubted = [...passed].filter((t) => !second.has(t) || !reached.has(t)).sort();
    const coverage = this.spoken(entries(byFile));
    const filesOf = new Map<string, Set<string>>();
    for (const [file, lines] of byFile) {
      for (const tests of lines.values()) {
        for (const t of tests) {
          let files = filesOf.get(t);
          if (files === undefined) filesOf.set(t, (files = new Set()));
          files.add(file);
        }
      }
    }
    if (doubted.length === 0) return { coverage, reached: filesOf, doubted: new Set() };
    return {
      coverage: {
        ...coverage,
        doubted: `${doubted.length} test(s) that passed did not pass in the run that counts coverage, or were not counted in it, so the lines they reach may be missing: ${doubted.slice(0, 3).join(", ")}${doubted.length > 3 ? ", …" : ""}`,
      },
      reached: filesOf,
      doubted: new Set(doubted),
    };
  }

  // Inline when it fits under the limit `hello` set, and packed in a file in
  // the reni when it does not, which is every project worth measuring.
  private spoken(all: wire.CoverageEntry[]): wire.Coverage {
    const size = all.reduce((n, e) => n + JSON.stringify(e).length + 1, 0);
    if (size <= (this.hello?.inline_limit_bytes ?? 0)) return { inline: all };
    const file = path.join(this.work, "coverage", "packed.json");
    writeFileSync(file, packed(all));
    return { packedPath: file };
  }

  private envDifference(): string[] {
    const kept = Object.keys(environment(this.hello?.env ?? {})).sort();
    return [
      `the tests see none of your environment but ${kept.join(", ")}: any other variable set for them in your shell is not there`,
    ];
  }

  private async cast(id: number, root: string, runner: Runner, c: CastRequest, signal: AbortSignal): Promise<string> {
    const started = Date.now();
    const asked = [...c.tests, ...(c.rest ?? [])];
    for (const t of asked) {
      if (parseId(root, t) === undefined) return wire.error(id, "unknown_test", `no test ${t}`, false);
    }
    const site = c.site;
    const file = path.join(root, site.file);
    let text: Buffer;
    try {
      text = readFileSync(file);
    } catch (e) {
      return wire.error(id, "bad_request", `cannot read \`${site.file}\`: ${(e as Error).message}`, false);
    }
    const from = site.start.byte;
    const to = site.end?.byte;
    if (to === undefined) return wire.error(id, "bad_request", "the site has no end", false);
    if (
      from < 0 ||
      to < from ||
      to > text.length ||
      (site.original !== undefined && text.subarray(from, to).toString("utf8") !== site.original)
    ) {
      return wire.error(id, "bad_request", `\`${site.file}\` is not the text this site was found in`, false);
    }
    if (asked.length === 0) {
      return wire.castDone(
        id,
        c.wekufe,
        { kind: "no_coverage", message: "no test was asked to judge it" },
        Date.now() - started,
      );
    }
    const served = path.join(this.work, "served");
    rmSync(served, { force: true });
    const wekufe = wekufeOf(file, splice(text, from, to, site.replacement).toString("utf8"), served);
    let outcome = judge(root, await runner.run(selection(root, c.tests), { wekufe }, signal), c.tests);
    if (outcome.kind === "survived" && c.rest !== undefined && c.rest.length > 0) {
      outcome = judge(root, await runner.run(selection(root, c.rest), { wekufe }, signal), c.rest);
    }
    return wire.castDone(id, c.wekufe, loaded(outcome, existsSync(served), site.file), Date.now() - started);
  }

  private sites(id: number, root: string, r: SitesRequest): string {
    const found: Site[] = [];
    const skipped: Skipped[] = [];
    for (const file of r.files) {
      const result = search(root, file, r);
      if ("reason" in result) skipped.push(result);
      else found.push(...result);
    }
    return wire.sitesFound(id, found, skipped);
  }
}

// Two tests with the same titles in one file are one id; the second is
// known by its place.
function unique(tests: TestResult[]): TestResult[] {
  const seen = new Map<string, number>();
  return tests.map((t) => {
    const key = t.names.join("\u0000");
    const n = (seen.get(key) ?? 0) + 1;
    seen.set(key, n);
    if (n === 1) return t;
    const names = [...t.names];
    names[names.length - 1] = `${names[names.length - 1]} (${n})`;
    return { ...t, names };
  });
}

// A path with its symlinks resolved, or as it is when there is nothing there:
// a root that does not exist is refused a little later, by name.
function real(p: string): string {
  try {
    return realpathSync(p);
  } catch {
    return p;
  }
}

// The fingerprints of the test files, worked out once per baseline: what
// every file shares, and what each one imports.
class Prints {
  private readonly imports: Imports;
  private readonly common: Shared;
  private readonly cache = new Map<string, { print: string; covers: Set<string> } | undefined>();

  constructor(
    private readonly root: string,
    runner: Runner,
  ) {
    this.imports = new Imports(root);
    const words = [`kalku-typescript ${VERSION}`, `node ${process.version}`, `${runner.name} ${runner.version}`];
    this.common = shared(root, this.imports, runner.setups(), testFile, configFile, SUITE_DIRS, words);
  }

  // A test file's fingerprint and the files it covers, or undefined when its
  // tests cannot all be named without running it, which leaves nothing to
  // compare an offer with.
  of(file: string): { print: string; covers: Set<string> } | undefined {
    if (this.cache.has(file)) return this.cache.get(file);
    let found: { print: string; covers: Set<string> } | undefined;
    try {
      const declared = declaredTests(file, readFileSync(path.join(this.root, file), "utf8"));
      if (declared !== undefined) {
        const own = new Set([...this.imports.closure(file), ...snapshots(this.root, file)]);
        found = { print: fingerprint(this.root, this.common, own), covers: new Set([...own, ...this.common.files]) };
      }
    } catch {
      found = undefined;
    }
    this.cache.set(file, found);
    return found;
  }

  /** The files that need not run, and their tests: every test the file declares is offered with its fingerprint. */
  reusable(offer: Map<string, string>): { files: string[]; tests: string[] } {
    const files = new Set<string>();
    for (const t of offer.keys()) {
      const parsed = parseId(this.root, t);
      if (parsed !== undefined) files.add(relative(this.root, parsed.file));
    }
    const out = { files: [] as string[], tests: [] as string[] };
    for (const file of [...files].sort()) {
      const known = this.of(file);
      if (known === undefined) continue;
      const declared = declaredTests(file, readFileSync(path.join(this.root, file), "utf8"))!;
      const ids = unique(
        declared.map((names) => ({ names, status: "passed" as const, durationMs: 0, failure: "" })),
      ).map((t) => testId(this.root, path.join(this.root, file), t.names));
      if (ids.length === 0 || !ids.every((id) => offer.get(id) === known.print)) continue;
      out.files.push(path.join(this.root, file));
      out.tests.push(...ids);
    }
    return out;
  }

  /**
   * The fingerprint a test of this file is given now: none when any test of
   * the file failed, is in doubt, or was seen to execute a source the
   * fingerprint does not cover.
   */
  standing(
    file: string,
    reached: Map<string, Set<string>>,
    failing: Set<string>,
    doubted: Set<string>,
    tests: wire.Timed[],
  ): string | undefined {
    const known = this.of(file);
    if (known === undefined) return undefined;
    for (const t of tests) {
      if (t.file !== file) continue;
      if (failing.has(t.test) || doubted.has(t.test)) return undefined;
      for (const f of reached.get(t.test) ?? []) if (!known.covers.has(f)) return undefined;
    }
    return known.print;
  }
}

// For each test file, its tests by their titles joined with spaces: the
// name Jest gives the test that is running, and how the list of titles a
// Vitest record carries is joined to be looked up. Tests that share their
// titles share a name the runner cannot tell apart, so what one of them ran
// is credited to all of them: too much costs a cast time, too little would
// make a survivor.
function namesByFile(root: string, ran: RunResult): Map<string, Map<string, string[]>> {
  const out = new Map<string, Map<string, string[]>>();
  for (const f of ran.files) {
    const byName = new Map<string, string[]>();
    const named = unique(f.tests);
    f.tests.forEach((t, i) => {
      const id = testId(root, f.file, named[i]!.names);
      const name = t.names.join(" ");
      byName.set(name, [...(byName.get(name) ?? []), id]);
    });
    out.set(relative(root, f.file), byName);
  }
  return out;
}

function selection(root: string, ids: string[]): Selection {
  const files = new Set<string>();
  const names: string[][] = [];
  for (const id of ids) {
    const parsed = parseId(root, id)!;
    files.add(parsed.file);
    names.push(parsed.names.map((n, i, all) => (i === all.length - 1 ? n.replace(/ \(\d+\)$/, "") : n)));
  }
  return { files: [...files], names };
}

// A test file the wekufe would not let compile is a compile error; one that
// did not run for any other reason, having run at the baseline, was stopped
// by the wekufe, which is a kill.
const COMPILER = /error TS\d+|\bTS\d{4,5}:|SyntaxError|Transform failed|Unexpected token|Failed to parse source/;

/**
 * What a run of the tests says about a wekufe. A test asked for that failed
 * is the kill; a file of the asked tests that did not run is a compile error
 * or a kill, as above; a test asked for that never ran is a crash.
 */
export function judge(root: string, ran: RunResult, asked: string[]): Outcome {
  if (ran.failed !== undefined) return { kind: "crashed", message: ran.failed };
  const byId = new Map<string, TestResult>();
  const files = new Map<string, FileResult>();
  for (const f of ran.files) {
    files.set(relative(root, f.file), f);
    for (const t of unique(f.tests)) byId.set(testId(root, f.file, t.names), t);
  }
  for (const id of asked) {
    if (byId.get(id)?.status === "failed") return { kind: "killed", killedBy: id };
  }
  const broken = asked.map((id) => files.get(fileOf(root, id))).find((f) => f?.broken !== undefined);
  if (broken !== undefined) {
    const said = `${relative(root, broken.file)}: ${broken.broken}`;
    return COMPILER.test(broken.broken!) ? { kind: "compile_error", message: said } : { kind: "killed" };
  }
  const missing = asked.filter((id) => byId.get(id)?.status !== "passed");
  if (missing.length > 0) {
    return {
      kind: "crashed",
      message: `${asked.length - missing.length} of ${asked.length} selected test(s) ran; ${missing[0]} did not`,
    };
  }
  return { kind: "survived" };
}

/**
 * A verdict stands only if the runner loaded the wekufe. One it never loaded
 * met no test, whatever the tests did: a test that failed then failed for
 * another reason, and one that passed passed against the original. Either
 * way nothing judged the wekufe, which is what `no_coverage` says.
 */
export function loaded(outcome: Outcome, served: boolean, file: string): Outcome {
  if (served || (outcome.kind !== "killed" && outcome.kind !== "survived")) return outcome;
  return { kind: "no_coverage", message: `no test loaded \`${file}\` while the wekufe was in it` };
}

function fileOf(root: string, id: string): string {
  const parsed = parseId(root, id);
  return parsed === undefined ? "" : relative(root, parsed.file);
}

function search(root: string, file: string, r: SitesRequest): Site[] | Skipped {
  const skip = (reason: string, message: string): Skipped => ({ file, reason, message });
  if (scriptKind(file) === undefined)
    return skip("not_typescript", `\`${file}\` is not a TypeScript or JavaScript source file`);
  // A declaration file holds no code: it has no sites, which is the whole truth about it.
  if (/\.d\.[mc]?ts$/.test(file)) return [];
  if (testFile(file)) return skip("test_file", `\`${file}\` is part of the suite, which is the oracle`);
  if (configFile(file)) {
    return skip(
      "config",
      `\`${file}\` configures the tools, which load it before any wekufe can be served in its place`,
    );
  }
  if (file.split("/").includes("node_modules"))
    return skip("dependency", `\`${file}\` is a dependency, not the project's own code`);
  let text: string;
  try {
    text = readFileSync(path.join(root, file), "utf8");
  } catch (e) {
    return skip("unreadable", (e as Error).message);
  }
  if (generated(text)) return skip("generated", "the file says it is generated, and nobody edits it by hand");
  try {
    return findSites(file, text, r.spells, r.exclude_calls);
  } catch (e) {
    if (e instanceof ParseError) return skip("parse_error", e.message);
    throw e;
  }
}

/** True for a tool's configuration: `vitest.config.ts`, `jest.config.js`, `.eslintrc.cjs`, `jest.setup.ts`. */
export function configFile(file: string): boolean {
  const name = file.split("/").pop() ?? "";
  return (
    /\.config\.[cm]?[jt]s$/.test(name) ||
    /^\.[\w-]+rc\.[cm]?js$/.test(name) ||
    /^(jest|vitest)\.setup\.[cm]?[jt]sx?$/.test(name)
  );
}

// The directories a project keeps its suite in, helpers and fixtures with it.
export const SUITE_DIRS = ["test", "tests", "__tests__", "__mocks__", "__fixtures__"];

/** True for a file that belongs to the suite: `*.test.ts`, `*.spec.ts`, anything in a directory the suite is kept in. */
export function testFile(file: string): boolean {
  const parts = file.split("/");
  if (parts.slice(0, -1).some((p) => SUITE_DIRS.includes(p))) return true;
  return /\.(test|spec)\.[cm]?[jt]sx?$/.test(parts[parts.length - 1] ?? "");
}
