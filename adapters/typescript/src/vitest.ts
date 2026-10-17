// Vitest, run through its own Node API from the project's node_modules. A
// plugin serves the wekufe as the text of its file, so Vite transforms it
// as it would the file itself, and nothing is written to the project: the
// cache is the reni's, and CI is set so that no snapshot is written.

import { availableParallelism } from "node:os";
import { readFileSync } from "node:fs";
import path from "node:path";
import { relative } from "./project";
import {
  PrepareError,
  asset,
  installed,
  namePattern,
  reported,
  type Context,
  type RunOptions,
  type RunResult,
  type Runner,
  type Selection,
} from "./runner";

// A path as a glob that matches only itself.
function glob(file: string): string {
  return file.replace(/[*?[\]{}()!]/g, "\\$&");
}

// The driver runs in the project's Node as an ES module, because Vitest
// is one. It takes one argument, a JSON request, and writes the report
// where the request says.
const DRIVER = `import { createRequire } from "node:module";
import { readFileSync, writeFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

const request = JSON.parse(readFileSync(process.argv[2], "utf8"));
const wekufe = request.wekufe ? JSON.parse(readFileSync(request.wekufe, "utf8")) : undefined;
const counting = request.counting;
const manifest = counting ? JSON.parse(readFileSync(counting.manifest, "utf8")) : {};
const required = createRequire(request.root + "/package.json");
const { startVitest } = await import(pathToFileURL(required.resolve("vitest/node")).href);
const { configDefaults } = await import(pathToFileURL(required.resolve("vitest/config")).href);

const plugin = {
  name: "kalku",
  enforce: "pre",
  config(config) {
    config.test = config.test || {};
    if (request.skip.length > 0) config.test.exclude = [...(config.test.exclude ?? configDefaults.exclude), ...request.skip];
    if (counting === undefined) return;
    const theirs = config.test.setupFiles;
    config.test.setupFiles = [counting.setup, ...(theirs === undefined ? [] : [theirs].flat())];
  },
  configResolved(config) {
    const listed = (v) => [v].flat().filter((f) => typeof f === "string" && f !== (counting && counting.setup));
    const test = config.test || {};
    writeFileSync(request.resolved, JSON.stringify({ root: config.root, setups: [...listed(test.setupFiles), ...listed(test.globalSetup)] }));
  },
  load(id) {
    const file = id.split("?")[0];
    if (manifest[file] !== undefined) return readFileSync(manifest[file], "utf8");
    if (wekufe === undefined || !wekufe.files.includes(file)) return undefined;
    writeFileSync(wekufe.served, "");
    return wekufe.text;
  },
};

try {
  const options = {
    root: request.root,
    run: true,
    watch: false,
    update: false,
    allowOnly: true,
    passWithNoTests: true,
    reporters: [["json", { outputFile: request.out }]],
    coverage: { enabled: false },
    // Vitest 5 keeps transformed modules on disk, inside node_modules, and
    // serves them past every plugin: the wekufe would never be loaded.
    fsModuleCache: false,
  };
  if (request.pattern !== undefined) options.testNamePattern = request.pattern;
  if (request.workers !== undefined) options.maxWorkers = request.workers;
  // Every file loads the modules it uses itself, so what a module does as
  // it loads is counted for each file whose tests it serves.
  if (counting !== undefined) options.isolate = true;
  const vitest = await startVitest("test", request.filters, options, { plugins: [plugin], cacheDir: request.cacheDir });
  await vitest?.close();
  process.exit(0);
} catch (e) {
  console.error(e && e.stack ? e.stack : String(e));
  process.exit(2);
}
`;

// The setup file a counting run loads before the project's own. It credits
// each count to the tests running when it happened, and what ran outside
// any test to the file, and writes one record a file.
const COUNTING = `import { afterAll, afterEach, beforeEach } from "vitest";
import { appendFileSync } from "node:fs";
import { join } from "node:path";

const shared = new Set();
const running = new Map();
const tests = [];
let file;

globalThis.__kalku = {
  h(n) {
    if (running.size === 0) shared.add(n);
    else for (const hits of running.values()) hits.add(n);
  },
};

function names(task) {
  const out = [];
  for (let t = task; t !== undefined && !("filepath" in t); t = t.suite) out.unshift(t.name);
  return out;
}

beforeEach((context) => {
  file = file ?? context.task.file.filepath;
  running.set(context.task.id, new Set());
});

afterEach((context) => {
  const hits = running.get(context.task.id) ?? new Set();
  running.delete(context.task.id);
  tests.push({ names: names(context.task), hits: [...hits] });
});

afterAll(() => {
  if (file === undefined) return;
  const line = JSON.stringify({ file, shared: [...shared], tests });
  appendFileSync(join(process.env.KALKU_COVERAGE_OUT, process.pid + ".ndjson"), line + "\\n");
});
`;

export class Vitest implements Runner {
  readonly name = "vitest" as const;
  private driver = "";

  constructor(
    private readonly ctx: Context,
    readonly version: string,
  ) {}

  static find(ctx: Context): Vitest | undefined {
    const found = installed(ctx.root, "vitest");
    return found === undefined ? undefined : new Vitest(ctx, found.version);
  }

  async prepare(): Promise<void> {
    const major = Number(this.version.split(".")[0]);
    if (!(major >= 3)) throw new PrepareError(`Vitest ${this.version} is older than 3, the oldest this kalku runs`);
    this.driver = asset(this.ctx, "vitest-driver.mjs", DRIVER);
  }

  async run(selection: Selection | "all", opts: RunOptions = {}, signal?: AbortSignal): Promise<RunResult> {
    const { wekufe, counting } = opts;
    const out = path.join(this.ctx.work, "vitest-report.json");
    const request: Record<string, unknown> = {
      root: this.ctx.root,
      out,
      filters: selection === "all" ? [] : selection.files,
      skip: (opts.skip ?? []).map((f: string) => glob(relative(this.ctx.root, f))),
      resolved: this.resolved,
      cacheDir: path.join(this.ctx.work, "vite-cache"),
    };
    if (selection !== "all") {
      // A worker per file asked for and no more: Vitest starts every worker it may use.
      request["workers"] = Math.max(1, Math.min(selection.files.length, availableParallelism()));
      if (selection.names !== undefined) request["pattern"] = namePattern(selection.names);
    }
    if (wekufe !== undefined) request["wekufe"] = asset(this.ctx, "wekufe.json", JSON.stringify(wekufe));
    const env: NodeJS.ProcessEnv = { ...this.ctx.env, CI: "true" };
    if (counting !== undefined) {
      request["counting"] = { ...counting, setup: asset(this.ctx, "vitest-counting.mjs", COUNTING) };
      env["KALKU_COVERAGE_OUT"] = counting.out;
    }
    const file = asset(this.ctx, "vitest-request.json", JSON.stringify(request));
    return reported(this.ctx, [this.driver, file], out, env, signal);
  }

  // What the configuration Vitest last resolved names for it to load
  // around the tests: its setup files and global setup.
  setups(): string[] {
    try {
      const said = JSON.parse(readFileSync(this.resolved, "utf8")) as { root: string; setups: string[] };
      return said.setups.map((f) => path.resolve(said.root, f));
    } catch {
      return [];
    }
  }

  private get resolved(): string {
    return path.join(this.ctx.work, "vitest-resolved.json");
  }

  differences(): string[] {
    return [
      `the tests ran through Vitest's Node API with its JSON reporter, not through the project's test script: anything the script does around \`vitest\` did not happen`,
      "CI=true was set, so a snapshot that does not exist yet fails instead of being written",
      "coverage was not collected, whatever the configuration says",
    ];
  }
}
