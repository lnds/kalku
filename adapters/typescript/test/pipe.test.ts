import assert from "node:assert/strict";
import { type ChildProcessWithoutNullStreams, execFileSync, spawn } from "node:child_process";
import { createHash } from "node:crypto";
import {
  cpSync,
  existsSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  statSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, describe, test } from "node:test";
import { ADAPTER, TESTDATA } from "./here";

// The kalku is driven as the kaikai side drives it: the built binary, a real
// pipe, a real project with its own test runner installed. Nothing smaller
// proves it works.
const BIN = path.join(ADAPTER, "bin/kalku-typescript");

// A reply that never comes fails the test instead of hanging the suite, and
// every kalku a test started ends with the suite, whatever became of it.
const PATIENCE_MS = 120_000;
const summoned: Kalku[] = [];
after(() => {
  for (const k of summoned) k.child.kill("SIGKILL");
});

class Kalku {
  readonly child: ChildProcessWithoutNullStreams;
  readonly said: string[] = [];
  reni = "";
  private buffer = "";
  private waiting: { id: number; resolve: (m: Record<string, unknown>) => void }[] = [];

  constructor() {
    this.child = spawn(BIN, [], { stdio: ["pipe", "pipe", "pipe"] });
    summoned.push(this);
    this.child.stderr.resume();
    this.child.stdout.on("data", (chunk: Buffer) => {
      this.buffer += chunk.toString("utf8");
      for (let at = this.buffer.indexOf("\n"); at !== -1; at = this.buffer.indexOf("\n")) {
        const line = this.buffer.slice(0, at);
        this.buffer = this.buffer.slice(at + 1);
        this.said.push(line);
        const m = JSON.parse(line) as Record<string, unknown>;
        const w = this.waiting.find((x) => x.id === m["id"]);
        if (w !== undefined) {
          this.waiting = this.waiting.filter((x) => x !== w);
          w.resolve(m);
        }
      }
    });
  }

  send(message: Record<string, unknown>): void {
    this.child.stdin.write(`${JSON.stringify(message)}\n`);
  }

  ask(message: Record<string, unknown>): Promise<Record<string, unknown>> {
    const reply = new Promise<Record<string, unknown>>((resolve, reject) => {
      const timer = setTimeout(
        () => reject(new Error(`no reply to ${JSON.stringify(message).slice(0, 200)}`)),
        PATIENCE_MS,
      );
      this.waiting.push({
        id: message["id"] as number,
        resolve: (m) => {
          clearTimeout(timer);
          resolve(m);
        },
      });
    });
    this.send(message);
    return reply;
  }

  async ended(): Promise<number | null> {
    if (this.child.exitCode !== null) return this.child.exitCode;
    return new Promise((resolve) => this.child.on("exit", (code) => resolve(code)));
  }
}

function hello(root: string, options?: Record<string, unknown>, limit = 65536): Record<string, unknown> {
  const reni = mkdtempSync(path.join(tmpdir(), "kalku-ts-"));
  return {
    type: "hello",
    id: 1,
    protocol: 1,
    root,
    reni,
    worker: 0,
    inline_limit_bytes: limit,
    env: {},
    ...(options ? { options } : {}),
  };
}

async function started(root: string, limit = 65536): Promise<Kalku> {
  const k = new Kalku();
  const greeting = hello(root, undefined, limit);
  k.reni = greeting["reni"] as string;
  const ready = await k.ask(greeting);
  assert.equal(ready["type"], "ready", JSON.stringify(ready));
  assert.equal((await k.ask({ type: "prepare", id: 2 }))["type"], "prepared");
  return k;
}

async function sitesOf(k: Kalku, files: string[]): Promise<Record<string, unknown>[]> {
  const found = await k.ask({
    type: "sites",
    id: 4,
    files,
    spells: ["arm", "compare", "connect", "negate", "literal", "call"],
    exclude_calls: [],
  });
  return found["sites"] as Record<string, unknown>[];
}

function site(
  sites: Record<string, unknown>[],
  file: string,
  spell: string,
  original: string,
): Record<string, unknown> {
  const s = sites.find((x) => x["file"] === file && x["spell"] === spell && x["original"] === original);
  assert.ok(s, `no ${spell} site on ${original} in ${file}`);
  return s;
}

function cast(id: number, s: Record<string, unknown>, tests: string[]): Record<string, unknown> {
  return { type: "cast", id, wekufe: s["site_id"], site: s, tests };
}

// What the project holds: its own files, and what a tool may have written
// into node_modules beside the packages, a cache most often. A kalku must
// leave all of it as it was.
function snapshot(root: string): string {
  const h = createHash("sha256");
  const walk = (dir: string): void => {
    for (const name of readdirSync(dir).sort()) {
      const p = path.join(dir, name);
      const st = statSync(p);
      if (name === "node_modules") written(p);
      else if (st.isDirectory()) walk(p);
      else h.update(`${p}\u0000${st.mtimeMs}\u0000`).update(readFileSync(p));
    }
  };
  const written = (modules: string): void => {
    for (const name of readdirSync(modules).sort()) {
      h.update(`${modules}/${name}\u0000`);
      if (name.startsWith(".") && name !== ".bin" && statSync(path.join(modules, name)).isDirectory()) {
        walk(path.join(modules, name));
      }
    }
  };
  walk(root);
  return h.digest("hex");
}

const reniOf = (k: Kalku): string => k.reni;

function processes(): { pid: number; pgid: number; command: string }[] {
  return execFileSync("ps", ["-axo", "pid=,pgid=,command="], { encoding: "utf8" })
    .split("\n")
    .map((l) => /^\s*(\d+)\s+(\d+)\s+(.*)$/.exec(l))
    .filter((m): m is RegExpExecArray => m !== null)
    .map((m) => ({ pid: Number(m[1]), pgid: Number(m[2]), command: m[3]! }));
}

// Every process in the groups led by a process whose command names the
// marker: a test runner and the workers it started, which do not name it.
function groups(marker: string): number[] {
  const all = processes();
  const leaders = new Set(all.filter((p) => p.command.includes(marker) && p.pid === p.pgid).map((p) => p.pid));
  return all.filter((p) => leaders.has(p.pgid)).map((p) => p.pid);
}

function alive(pids: number[]): number[] {
  const now = new Set(processes().map((p) => p.pid));
  return pids.filter((p) => now.has(p));
}

const TESTS = {
  above: "test/calc.test.ts:above › is true at the floor and below it is false",
  total: "test/calc.test.ts:total adds",
  totalAgain: "test/calc.test.ts:total adds (2)",
  clamp: "test/calc.test.ts:clamp keeps a positive number",
  wrap: "test/bounds.test.ts:wrapping stays inside the range",
  settle: "test/settle.test.ts:settle reaches zero",
  mode: "test/mode.test.ts:mode names the speed",
};

// Each fixture is a project of its own, with the runner it names installed,
// and the oldest Node that runner runs on.
const PROJECTS = [
  { dir: "vitest", runner: "vitest", runtime: /^Node \d+\.\d+\.\d+, Vitest 3\./, node: 20 },
  { dir: "vitest5", runner: "vitest", runtime: /^Node \d+\.\d+\.\d+, Vitest 5\./, node: 22 },
  { dir: "jest", runner: "jest", runtime: /^Node \d+\.\d+\.\d+, Jest 30\./, node: 20 },
] as const;

const NODE = Number(process.versions.node.split(".")[0]);

for (const { dir, runner, runtime, node } of PROJECTS) {
  const root = path.join(TESTDATA, "projects", dir);
  const installed = existsSync(path.join(root, "node_modules", runner));
  const skip =
    NODE < node
      ? `${dir} needs Node ${node}`
      : !installed && process.env["CI"] === undefined
        ? `run \`npm ci\` in testdata/projects/${dir} first`
        : false;

  describe(`against a project that tests with ${dir}`, { skip }, () => {
    test("it greets naming the runner, and says only protocol on stdout", async () => {
      const k = await started(root);
      const ready = JSON.parse(k.said[0]!) as Record<string, unknown>;
      assert.match(String(ready["runtime"]), runtime);
      assert.deepEqual(ready["capabilities"], [
        "cast",
        "per_test_coverage",
        "abort",
        "cast_after_baseline",
        "partial_baseline",
      ]);
      const done = await k.ask({ type: "baseline", id: 3 });
      assert.equal(done["status"], "green");
      await k.ask({ type: "shutdown", id: 9 });
      assert.equal(await k.ended(), 0);
      for (const line of k.said) assert.doesNotThrow(() => JSON.parse(line), line);
    });

    test("a baseline names every test by its file and titles", async () => {
      const k = await started(root);
      const done = await k.ask({ type: "baseline", id: 3 });
      const names = (done["tests"] as { test: string }[]).map((t) => t.test).sort();
      assert.deepEqual(names, Object.values(TESTS).sort());
      assert.deepEqual(done["failures"], []);
      assert.ok((done["differences"] as string[]).some((d) => d.includes("not through the project's test script")));
      await k.ask({ type: "shutdown", id: 9 });
    });

    test("a baseline credits each line with the tests that run it, and no others", async () => {
      const k = await started(root);
      const done = await k.ask({ type: "baseline", id: 3 });
      assert.equal(done["coverage_doubted"], undefined);
      const coverage = done["coverage"] as { file: string; line: number; tests: string[] }[];
      const at = (file: string, line: number): string[] | undefined =>
        coverage.find((e) => e.file === file && e.line === line)?.tests;
      assert.deepEqual(at("src/calc.ts", 5), [TESTS.above]);
      assert.deepEqual(at("src/bounds.ts", 2), [TESTS.clamp]);
      assert.deepEqual(at("src/settle.ts", 4), [TESTS.settle]);
      assert.equal(at("src/calc.ts", 1), undefined, "an import runs nothing a test is credited with");
      assert.ok(!coverage.some((e) => e.file.startsWith("test/")), "the suite is not its own coverage");
      await k.ask({ type: "shutdown", id: 9 });
    });

    test("coverage past the inline limit is packed into a file in the reni", async () => {
      const k = await started(root, 10);
      const done = await k.ask({ type: "baseline", id: 3 });
      assert.equal(done["coverage"], undefined);
      const file = done["coverage_packed_path"] as string;
      assert.ok(file.startsWith(k.reni), file);
      const packed = JSON.parse(readFileSync(file, "utf8")) as {
        tests: string[];
        reached: { file: string; lines: number[]; tests: number[] }[];
      };
      const above = packed.tests.indexOf(TESTS.above);
      assert.ok(packed.reached.some((r) => r.file === "src/calc.ts" && r.lines.includes(5) && r.tests.includes(above)));
      await k.ask({ type: "shutdown", id: 9 });
    });

    test("a baseline offered what it may reuse leaves out the files nothing changed under, and runs the one that did", async () => {
      // A copy, so that a file can be changed; its packages are the fixture's.
      const copy = mkdtempSync(path.join(tmpdir(), "kalku-ts-project-"));
      cpSync(root, copy, { recursive: true, filter: (src) => !src.includes(`${path.sep}node_modules`) });
      symlinkSync(path.join(root, "node_modules"), path.join(copy, "node_modules"));
      const first = await started(copy);
      const done = await first.ask({ type: "baseline", id: 3 });
      const timed = done["tests"] as { test: string; fingerprint?: string }[];
      for (const t of timed) assert.ok(t.fingerprint, `${t.test} has no fingerprint`);
      await first.ask({ type: "shutdown", id: 9 });
      const reuse = timed.map((t) => ({ test: t.test, fingerprint: t.fingerprint }));

      const again = await started(copy);
      const all = await again.ask({ type: "baseline", id: 3, reuse });
      assert.deepEqual(all["tests"], []);
      assert.deepEqual([...(all["reused"] as string[])].sort(), Object.values(TESTS).sort());
      await again.ask({ type: "shutdown", id: 9 });

      // bounds.ts is imported by calc.ts and by the bounds test; nothing else.
      const bounds = path.join(copy, "src/bounds.ts");
      writeFileSync(bounds, readFileSync(bounds, "utf8") + "\nexport const SPARE = 1;\n");
      const after = await started(copy);
      const some = await after.ask({ type: "baseline", id: 3, reuse });
      const ran = (some["tests"] as { test: string }[]).map((t) => t.test).sort();
      assert.deepEqual(ran, [TESTS.wrap, TESTS.above, TESTS.clamp, TESTS.total, TESTS.totalAgain].sort());
      assert.deepEqual([...(some["reused"] as string[])].sort(), [TESTS.mode, TESTS.settle].sort());
      await after.ask({ type: "shutdown", id: 9 });
    });

    test("a wekufe a test notices is killed by it, and one none notices survives", async () => {
      const before = snapshot(root);
      const k = await started(root);
      const sites = await sitesOf(k, ["src/calc.ts", "src/bounds.ts"]);
      const killed = await k.ask(cast(5, site(sites, "src/calc.ts", "compare", ">="), [TESTS.above, TESTS.total]));
      assert.equal(killed["outcome"], "killed");
      assert.equal(killed["killed_by"], TESTS.above);
      const lived = await k.ask(cast(6, site(sites, "src/bounds.ts", "compare", ">"), [TESTS.clamp]));
      assert.equal(lived["outcome"], "survived");
      await k.ask({ type: "shutdown", id: 9 });
      assert.equal(snapshot(root), before, "the project changed");
    });

    test("a wekufe no test loads met no test, whatever the tests did", async () => {
      const k = await started(root);
      const sites = await sitesOf(k, ["src/settle.ts"]);
      const done = await k.ask(cast(5, site(sites, "src/settle.ts", "compare", ">"), [TESTS.total]));
      assert.equal(done["outcome"], "no_coverage");
      assert.match(String(done["message"]), /no test loaded `src\/settle\.ts`/);
      await k.ask({ type: "shutdown", id: 9 });
    });

    test("a wekufe that no longer fits its type is a compile error where the runner checks types", async () => {
      const k = await started(root);
      const sites = await sitesOf(k, ["src/mode.ts"]);
      const done = await k.ask(cast(5, site(sites, "src/mode.ts", "literal", '"fast"'), [TESTS.mode]));
      if (runner === "jest") {
        assert.equal(done["outcome"], "compile_error");
        assert.match(String(done["message"]), /src\/mode\.ts|TS2322/);
      } else {
        assert.equal(done["outcome"], "killed");
      }
      await k.ask({ type: "shutdown", id: 9 });
    });

    test("a wekufe that loops forever is aborted in place, and the same kalku casts the next", async () => {
      const k = await started(root);
      const sites = await sitesOf(k, ["src/settle.ts", "src/calc.ts"]);
      k.send(cast(5, site(sites, "src/settle.ts", "negate", "i > 0"), [TESTS.settle]));
      await new Promise((r) => setTimeout(r, 3000));
      const spinning = groups(reniOf(k));
      assert.ok(spinning.length > 0, "the cast started no test runner");
      const aborted = await k.ask({ type: "abort", id: 6, cast: 5 });
      assert.deepEqual(aborted, { type: "aborted", id: 6, cast: 5, restored: true });
      assert.ok(!k.said.some((l) => l.includes('"type":"cast_done","id":5')), "an aborted cast was answered");
      const next = await k.ask(cast(7, site(sites, "src/calc.ts", "compare", ">="), [TESTS.above]));
      assert.equal(next["outcome"], "killed");
      await k.ask({ type: "shutdown", id: 9 });
      assert.deepEqual(alive(spinning), [], "the aborted cast left processes behind");
    });

    test("a kalku whose run is gone ends in the middle of a cast, with what it started", async () => {
      const k = await started(root);
      const sites = await sitesOf(k, ["src/settle.ts"]);
      k.send(cast(5, site(sites, "src/settle.ts", "negate", "i > 0"), [TESTS.settle]));
      await new Promise((r) => setTimeout(r, 3000));
      const spinning = groups(reniOf(k));
      assert.ok(spinning.length > 0, "the cast started no test runner");
      k.child.stdin.end();
      assert.notEqual(await k.ended(), null);
      await new Promise((r) => setTimeout(r, 500));
      assert.deepEqual(alive(spinning), [], "the kalku left processes behind");
    });

    test("a test it never ran is unknown, and a setting it does not take is refused", async () => {
      const k = await started(root);
      const sites = await sitesOf(k, ["src/calc.ts"]);
      const unknown = await k.ask(
        cast(5, site(sites, "src/calc.ts", "compare", ">="), ["test/nothing.test.ts:no such test"]),
      );
      assert.equal(unknown["code"], "unknown_test");
      await k.ask({ type: "shutdown", id: 9 });
      const other = new Kalku();
      const refused = await other.ask(hello(root, { oracle: "make test" }));
      assert.equal(refused["code"], "bad_options");
      other.child.stdin.end();
    });
  });
}
