// The kalku protocol as this kalku speaks it: requests decoded with the
// error kinds `docs/protocol.md` names, and replies written in canonical
// form, which `JSON.stringify` already is for strings once the fields are
// put in the order the tables list them and the absent ones left out.

export const PROTOCOL = 1;

/** The longest line, in bytes, the channel carries. */
export const MAX_LINE = 4 * 1024 * 1024;

export const ALL_SPELLS = [
  "arm",
  "compare",
  "connect",
  "negate",
  "literal",
  "call",
  "return",
  "await",
  "supervise",
  "foreign",
] as const;
export type Spell = (typeof ALL_SPELLS)[number];

/** What this kalku casts: `await` and `supervise` are for runtimes it does not judge. */
export const SPELLS: Spell[] = ["arm", "compare", "connect", "negate", "literal", "call", "return"];

export interface Position {
  line: number;
  col: number;
  byte: number;
}

export interface Site {
  site_id: string;
  file: string;
  enclosing?: string;
  ordinal?: number;
  start: Position;
  end?: Position;
  spell: Spell;
  original?: string;
  replacement: string;
  reload: "module" | "dependents";
}

export interface Hello {
  protocol: number;
  root: string;
  reni: string;
  worker: number;
  inline_limit_bytes: number;
  env: Record<string, string>;
  options?: Record<string, unknown>;
}

export interface SitesRequest {
  files: string[];
  spells: Spell[];
  exclude_calls: string[];
}

export interface CastRequest {
  wekufe: string;
  site: Site;
  tests: string[];
  rest?: string[];
}

export type Request =
  | { type: "hello"; id: number; hello: Hello }
  | { type: "prepare"; id: number }
  | { type: "baseline"; id: number; reuse?: Map<string, string> }
  | { type: "sites"; id: number; sites: SitesRequest }
  | { type: "cast"; id: number; cast: CastRequest }
  | { type: "abort"; id: number; cast: number }
  | { type: "reset"; id: number }
  | { type: "reload"; id: number; files: string[] }
  | { type: "shutdown"; id: number }
  | { type: "delegate"; id: number };

/** A line that is not a request this kalku can accept, with the kind the protocol names. */
export class DecodeError extends Error {
  constructor(
    readonly kind: string,
    readonly detail: string,
    readonly id?: number,
  ) {
    super(`${kind}: ${detail}`);
  }
}

type Json = null | boolean | number | string | Json[] | { [k: string]: Json };
type Obj = { [k: string]: Json };

function isObject(v: unknown): v is Obj {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

// Field readers that record the first problem and go on, so one decode
// names the first bad field the way the other kalku do.
class Fields {
  problem = "";

  constructor(private readonly o: Obj) {}

  fail(path: string, why: string): void {
    if (this.problem === "") this.problem = `${path}: ${why}`;
  }

  present(name: string): boolean {
    return this.o[name] !== undefined && this.o[name] !== null;
  }

  raw(name: string): Json | undefined {
    const v = this.o[name];
    return v === null ? undefined : v;
  }

  int(name: string, from: Obj = this.o, path = name): number {
    const v = from[name];
    if (typeof v !== "number" || !Number.isInteger(v)) {
      this.fail(path, "expected an integer");
      return 0;
    }
    return v;
  }

  str(name: string, from: Obj = this.o, path = name): string {
    const v = from[name];
    if (typeof v !== "string") {
      this.fail(path, "expected a string");
      return "";
    }
    return v;
  }

  strs(name: string, from: Obj = this.o, path = name): string[] {
    const v = from[name];
    if (!Array.isArray(v) || v.some((x) => typeof x !== "string")) {
      this.fail(path, "expected a list of strings");
      return [];
    }
    return v as string[];
  }

  spells(name: string): Spell[] {
    const names = this.strs(name);
    for (const s of names) {
      if (!(ALL_SPELLS as readonly string[]).includes(s)) this.fail(name, `unknown spell \`${s}\``);
    }
    return names as Spell[];
  }

  env(name: string): Record<string, string> {
    const v = this.o[name];
    if (!isObject(v)) {
      this.fail(name, "expected an object of strings");
      return {};
    }
    const out: Record<string, string> = {};
    for (const [k, x] of Object.entries(v)) {
      if (typeof x !== "string") this.fail(`${name}.${k}`, "expected a string");
      else out[k] = x;
    }
    return out;
  }

  position(v: Json | undefined, path: string): Position {
    if (!isObject(v)) {
      this.fail(path, "expected a position");
      return { line: 0, col: 0, byte: 0 };
    }
    return {
      line: this.int("line", v, `${path}.line`),
      col: this.int("col", v, `${path}.col`),
      byte: this.int("byte", v, `${path}.byte`),
    };
  }

  site(v: Json | undefined, path: string): Site {
    if (!isObject(v)) {
      this.fail(path, "expected a site");
      return {
        site_id: "",
        file: "",
        start: { line: 0, col: 0, byte: 0 },
        spell: "arm",
        replacement: "",
        reload: "module",
      };
    }
    const span = v["span"];
    const spanObj = isObject(span) ? span : (this.fail(`${path}.span`, "expected a span"), {} as Obj);
    const spell = this.str("spell", v, `${path}.spell`);
    if (!(ALL_SPELLS as readonly string[]).includes(spell)) this.fail(`${path}.spell`, `unknown spell \`${spell}\``);
    const reload = this.str("reload", v, `${path}.reload`);
    if (reload !== "module" && reload !== "dependents") this.fail(`${path}.reload`, `unknown reload \`${reload}\``);
    const site: Site = {
      site_id: this.str("site_id", v, `${path}.site_id`),
      file: this.str("file", v, `${path}.file`),
      start: this.position(spanObj["start"], `${path}.span.start`),
      spell: spell as Spell,
      replacement: this.str("replacement", v, `${path}.replacement`),
      reload: reload as Site["reload"],
    };
    if (v["enclosing"] != null) site.enclosing = this.str("enclosing", v, `${path}.enclosing`);
    if (v["ordinal"] != null) site.ordinal = this.int("ordinal", v, `${path}.ordinal`);
    if (spanObj["end"] != null) site.end = this.position(spanObj["end"], `${path}.span.end`);
    if (v["original"] != null) site.original = this.str("original", v, `${path}.original`);
    return site;
  }

  reuse(name: string): Map<string, string> {
    const v = this.o[name];
    const out = new Map<string, string>();
    if (!Array.isArray(v)) {
      this.fail(name, "expected a list");
      return out;
    }
    v.forEach((entry, i) => {
      if (!isObject(entry)) {
        this.fail(`${name}[${i}]`, "expected an object");
        return;
      }
      out.set(
        this.str("test", entry, `${name}[${i}].test`),
        this.str("fingerprint", entry, `${name}[${i}].fingerprint`),
      );
    });
    return out;
  }

  scope(name: string): void {
    const v = this.o[name];
    if (!isObject(v)) {
      this.fail(name, "expected a scope");
      return;
    }
    const keys = ["all", "since", "files"].filter((k) => v[k] !== undefined && v[k] !== null);
    if (keys.length !== 1) {
      this.fail(name, "exactly one of `all`, `since` and `files`");
      return;
    }
    if (keys[0] === "all" && v["all"] !== true) this.fail(`${name}.all`, "expected true");
    if (keys[0] === "since") this.str("since", v, `${name}.since`);
    if (keys[0] === "files") this.strs("files", v, `${name}.files`);
  }
}

/** Decodes one line of the channel into a request, or says why it cannot. */
export function decode(line: string): Request {
  let v: unknown;
  try {
    v = JSON.parse(line);
  } catch (e) {
    throw new DecodeError("not_json", (e as Error).message);
  }
  if (!isObject(v)) throw new DecodeError("not_object", "the line is not a JSON object");
  const type = v["type"];
  if (typeof type !== "string") throw new DecodeError("missing_type", "no string `type`");
  const id = v["id"];
  if (typeof id !== "number" || !Number.isInteger(id)) throw new DecodeError("missing_id", "no integer `id`");
  const f = new Fields(v);
  let r: Request;
  switch (type) {
    case "hello": {
      const hello: Hello = {
        protocol: f.int("protocol"),
        root: f.str("root"),
        reni: f.str("reni"),
        worker: f.int("worker"),
        inline_limit_bytes: f.int("inline_limit_bytes"),
        env: f.env("env"),
      };
      if (f.present("options")) {
        const o = f.raw("options");
        if (isObject(o)) hello.options = o;
        else f.fail("options", "expected an object");
      }
      r = { type, id, hello };
      break;
    }
    case "prepare":
    case "reset":
    case "shutdown":
      r = { type, id };
      break;
    case "baseline":
      r = f.present("reuse") ? { type, id, reuse: f.reuse("reuse") } : { type, id };
      break;
    case "sites":
      r = {
        type,
        id,
        sites: { files: f.strs("files"), spells: f.spells("spells"), exclude_calls: f.strs("exclude_calls") },
      };
      break;
    case "cast": {
      const cast: CastRequest = {
        wekufe: f.str("wekufe"),
        site: f.site(f.raw("site"), "site"),
        tests: f.strs("tests"),
      };
      if (f.present("rest")) cast.rest = f.strs("rest");
      r = { type, id, cast };
      break;
    }
    case "abort":
      r = { type, id, cast: f.int("cast") };
      break;
    case "reload":
      r = { type, id, files: f.strs("files") };
      break;
    case "delegate":
      f.scope("scope");
      if (f.present("spells")) f.spells("spells");
      r = { type, id };
      break;
    default:
      throw new DecodeError("unknown_type", type, id);
  }
  if (f.problem !== "") throw new DecodeError("bad_field", f.problem, id);
  return r;
}

// ---- replies ---------------------------------------------------------------

function line(type: string, id: number, fields: Record<string, unknown>): string {
  return JSON.stringify({ type, id, ...fields });
}

export function positionJson(p: Position): Record<string, number> {
  return { line: p.line, col: p.col, byte: p.byte };
}

/** A site in canonical order, for the wire and for golden files. */
export function siteJson(s: Site): string {
  return JSON.stringify(siteObject(s));
}

function siteObject(s: Site): Record<string, unknown> {
  return {
    site_id: s.site_id,
    file: s.file,
    enclosing: s.enclosing,
    ordinal: s.ordinal,
    span: { start: positionJson(s.start), end: s.end === undefined ? undefined : positionJson(s.end) },
    spell: s.spell,
    original: s.original,
    replacement: s.replacement,
    reload: s.reload,
  };
}

export function ready(id: number, adapter: string, runtime: string, capabilities: string[]): string {
  return line("ready", id, {
    protocol: PROTOCOL,
    language: "typescript",
    adapter,
    runtime,
    spells: SPELLS,
    capabilities,
  });
}

export function error(id: number, code: string, message: string, fatal: boolean): string {
  return line("error", id, { code, message, fatal });
}

export function bye(id: number): string {
  return line("bye", id, {});
}

export function prepared(id: number, durationMs: number, modules: number): string {
  return line("prepared", id, { duration_ms: durationMs, modules });
}

export function progress(id: number, done: number, total: number): string {
  return line("progress", id, { done, total });
}

export interface Skipped {
  file: string;
  reason: string;
  message: string;
  /** Not the project's code to measure, rather than a file that could not be searched. */
  by_design?: true;
}

export function sitesFound(id: number, sites: Site[], skipped: Skipped[]): string {
  return line("sites_found", id, { sites: sites.map(siteObject), skipped });
}

export interface Timed {
  test: string;
  file: string;
  duration_ms: number;
  fingerprint?: string;
}

export interface Failure {
  test: string;
  message: string;
}

export interface CoverageEntry {
  file: string;
  line: number;
  tests: string[];
}

/** At most one of the ways coverage is said; none when the kalku measured none. */
export interface Coverage {
  inline?: CoverageEntry[];
  path?: string;
  packedPath?: string;
  withheld?: string;
  doubted?: string;
}

export function baselineDone(
  id: number,
  durationMs: number,
  tests: Timed[],
  coverage: Coverage,
  failures: Failure[],
  differences: string[] | undefined,
  reused: string[] | undefined,
): string {
  return line("baseline_done", id, {
    status: failures.length === 0 ? "green" : "red",
    duration_ms: durationMs,
    tests: tests.map((t) => ({ test: t.test, file: t.file, duration_ms: t.duration_ms, fingerprint: t.fingerprint })),
    coverage: coverage.inline?.map((e) => ({ file: e.file, line: e.line, tests: e.tests })),
    coverage_path: coverage.path,
    coverage_packed_path: coverage.packedPath,
    coverage_withheld: coverage.withheld,
    coverage_doubted: coverage.doubted,
    failures: failures.map((f) => ({ test: f.test, message: f.message })),
    differences: differences === undefined || differences.length === 0 ? undefined : differences,
    ...(reused === undefined || reused.length === 0 ? {} : { reused }),
  });
}

export type OutcomeKind = "killed" | "survived" | "compile_error" | "equivalent" | "no_coverage" | "crashed";

export interface Outcome {
  kind: OutcomeKind;
  killedBy?: string;
  message?: string;
}

export function castDone(id: number, wekufe: string, outcome: Outcome, durationMs: number): string {
  return line("cast_done", id, {
    wekufe,
    outcome: outcome.kind,
    killed_by: outcome.killedBy,
    message: outcome.message,
    duration_ms: durationMs,
    dirty: false,
  });
}

export function aborted(id: number, cast: number, restored: boolean): string {
  return line("aborted", id, { cast, restored });
}

export function resetDone(id: number, clean: boolean, durationMs: number): string {
  return line("reset_done", id, { clean, duration_ms: durationMs });
}

export function reloaded(id: number, modules: string[], durationMs: number): string {
  return line("reloaded", id, { modules, dependents: [], duration_ms: durationMs });
}
