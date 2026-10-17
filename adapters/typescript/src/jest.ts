// Jest, run through its own command from the project's node_modules, with
// the project's configuration as Jest resolves it. Every transform it
// configures is wrapped, so the wekufe reaches the project's own
// transformer (ts-jest, babel-jest, …) as the text of its file, and the
// transformer's cache is keyed by that text. `--ci` keeps snapshots from
// being written, and the cache is the reni's.

import { realpathSync } from "node:fs";
import path from "node:path";
import { run } from "./process";
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

function real(file: string): string {
  try {
    return realpathSync(file);
  } catch {
    return file;
  }
}

function escape(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

// The wrapper Jest loads as a transformer. It is CommonJS, which every
// Jest loads, and it delegates to the transformer it wraps.
const WRAPPER = `"use strict";
const { createHash } = require("node:crypto");
const { readFileSync, writeFileSync } = require("node:fs");

const wekufe = process.env.KALKU_WEKUFE ? JSON.parse(readFileSync(process.env.KALKU_WEKUFE, "utf8")) : undefined;
const manifest = process.env.KALKU_COVERAGE_MANIFEST ? JSON.parse(readFileSync(process.env.KALKU_COVERAGE_MANIFEST, "utf8")) : {};

// Asked for its cache key, the wrapper is asked for the file whether or not
// the transform is cached: that is where the wekufe is known to be loaded.
function served(text, file) {
  if (manifest[file] !== undefined) return readFileSync(manifest[file], "utf8");
  if (wekufe === undefined || !wekufe.files.includes(file)) return text;
  writeFileSync(wekufe.served, "");
  return wekufe.text;
}

function hash(...parts) {
  return createHash("sha256").update(parts.join("\\u0000")).digest("hex");
}

module.exports = {
  createTransformer(config) {
    const loaded = require(config.inner);
    const mod = loaded && loaded.__esModule && loaded.default ? loaded.default : loaded;
    const inner = typeof mod.createTransformer === "function" ? mod.createTransformer(config.options) : mod;
    const wrapped = {
      canInstrument: inner.canInstrument,
      getCacheKey(text, file, options) {
        const t = served(text, file);
        return hash(config.inner, inner.getCacheKey ? inner.getCacheKey(t, file, options) : "", t, file);
      },
    };
    if (inner.process) wrapped.process = (text, file, options) => inner.process(served(text, file), file, options);
    if (inner.processAsync) wrapped.processAsync = (text, file, options) => inner.processAsync(served(text, file), file, options);
    if (inner.getCacheKeyAsync) {
      wrapped.getCacheKeyAsync = async (text, file, options) => {
        const t = served(text, file);
        return hash(config.inner, await inner.getCacheKeyAsync(t, file, options), t, file);
      };
    }
    return wrapped;
  },
};
`;

interface Resolved {
  rootDir: string;
  transform: [string, string, unknown][];
  setupFiles: string[];
  setupFilesAfterEnv: string[];
  globalSetup?: string | null;
  globalTeardown?: string | null;
  testPathIgnorePatterns: string[];
}

// The setup file a counting run loads before the project's own. It credits
// each count to the test running when it happened, and what ran outside any
// test to the file, and writes one record a file.
const COUNTING = `"use strict";
const { appendFileSync } = require("node:fs");
const { join } = require("node:path");

const shared = new Set();
const tests = [];
let current;

globalThis.__kalku = {
  h(n) {
    (current ?? shared).add(n);
  },
};

beforeEach(() => {
  current = new Set();
});

afterEach(() => {
  tests.push({ names: expect.getState().currentTestName, hits: [...(current ?? [])] });
  current = undefined;
});

afterAll(() => {
  const line = JSON.stringify({ file: expect.getState().testPath, shared: [...shared], tests });
  appendFileSync(join(process.env.KALKU_COVERAGE_OUT, process.pid + ".ndjson"), line + "\\n");
});
`;

export class Jest implements Runner {
  readonly name = "jest" as const;
  private transform = "";
  private config: Resolved | undefined;

  constructor(
    private readonly ctx: Context,
    readonly version: string,
    private readonly bin: string,
  ) {}

  static find(ctx: Context): Jest | undefined {
    const found = installed(ctx.root, "jest");
    if (found === undefined) return undefined;
    const bin = found.json["bin"];
    const relative =
      typeof bin === "string" ? bin : ((bin as Record<string, string> | undefined)?.["jest"] ?? "bin/jest.js");
    return new Jest(ctx, found.version, path.join(found.dir, relative));
  }

  async prepare(signal?: AbortSignal): Promise<void> {
    const major = Number(this.version.split(".")[0]);
    if (!(major >= 29)) throw new PrepareError(`Jest ${this.version} is older than 29, the oldest this kalku runs`);
    const shown = await run(process.execPath, [this.bin, "--showConfig"], {
      cwd: this.ctx.root,
      env: this.ctx.env,
      keep: true,
      ...(signal ? { signal } : {}),
    });
    let configs: Resolved[];
    try {
      configs = (JSON.parse(shown.stdout) as { configs: Resolved[] }).configs;
    } catch {
      throw new PrepareError(
        `Jest could not read the project's configuration:\n${shown.said || shown.stdout.slice(0, 2000)}`,
      );
    }
    if (configs.length !== 1) {
      throw new PrepareError(
        `the Jest configuration has ${configs.length} projects; this kalku runs a configuration with one`,
      );
    }
    const wrapper = asset(this.ctx, "jest-transform.cjs", WRAPPER);
    const transform: Record<string, [string, unknown]> = {};
    for (const [pattern, inner, options] of configs[0]!.transform) {
      transform[pattern] = [wrapper, { inner, options }];
    }
    this.transform = JSON.stringify(transform);
    this.config = configs[0]!;
  }

  setups(): string[] {
    const c = this.config;
    if (c === undefined) return [];
    const loaded = [...c.setupFiles, ...c.setupFilesAfterEnv, c.globalSetup, c.globalTeardown];
    return loaded.filter((f): f is string => typeof f === "string" && f.startsWith(this.ctx.root));
  }

  async run(selection: Selection | "all", opts: RunOptions = {}, signal?: AbortSignal): Promise<RunResult> {
    const { wekufe, counting } = opts;
    const out = path.join(this.ctx.work, "jest-report.json");
    const args = [
      this.bin,
      "--ci",
      "--json",
      `--outputFile=${out}`,
      `--cacheDirectory=${path.join(this.ctx.work, "jest-cache")}`,
      `--transform=${this.transform}`,
      "--coverage=false",
      "--watchman=false",
      "--passWithNoTests",
      "--forceExit",
    ];
    if (selection !== "all") {
      if (selection.names !== undefined) args.push(`--testNamePattern=${namePattern(selection.names)}`);
      args.push("--runTestsByPath", ...selection.files);
    }
    // The command line's lists replace the configuration's, so the
    // project's own entries are named beside the kalku's.
    if (counting !== undefined) {
      const theirs = this.config?.setupFilesAfterEnv ?? [];
      args.push("--setupFilesAfterEnv", asset(this.ctx, "jest-counting.cjs", COUNTING), ...theirs);
    }
    if (opts.skip !== undefined && opts.skip.length > 0) {
      const theirs = this.config?.testPathIgnorePatterns ?? ["/node_modules/"];
      // Jest matches a test file by its real path, which may be another
      // spelling of the one the kalku was given.
      const spellings = opts.skip.flatMap((f) => [...new Set([f, real(f)])]);
      args.push("--testPathIgnorePatterns", ...theirs, ...spellings.map((f) => `^${escape(f)}$`));
    }
    const env: NodeJS.ProcessEnv = { ...this.ctx.env };
    if (counting !== undefined) {
      env["KALKU_COVERAGE_MANIFEST"] = counting.manifest;
      env["KALKU_COVERAGE_OUT"] = counting.out;
    }
    if (wekufe !== undefined) env["KALKU_WEKUFE"] = asset(this.ctx, "wekufe.json", JSON.stringify(wekufe));
    return reported(this.ctx, args, out, env, signal);
  }

  differences(): string[] {
    return [
      "the tests ran through Jest's own command with `--ci`, not through the project's test script: anything the script does around `jest` did not happen, and a snapshot that does not exist yet fails instead of being written",
      "coverage was not collected, whatever the configuration says, and Jest was told to exit when the tests were done (`--forceExit`)",
    ];
  }
}
