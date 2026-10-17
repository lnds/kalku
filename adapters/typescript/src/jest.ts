// Jest, run through its own command from the project's node_modules, with
// the project's configuration as Jest resolves it. Every transform it
// configures is wrapped, so the wekufe reaches the project's own
// transformer (ts-jest, babel-jest, …) as the text of its file, and the
// transformer's cache is keyed by that text. `--ci` keeps snapshots from
// being written, and the cache is the reni's.

import path from "node:path";
import { run } from "./process";
import {
  PrepareError,
  asset,
  installed,
  namePattern,
  reported,
  type Context,
  type RunResult,
  type Runner,
  type Selection,
  type Wekufe,
} from "./runner";

// The wrapper Jest loads as a transformer. It is CommonJS, which every
// Jest loads, and it delegates to the transformer it wraps.
const WRAPPER = `"use strict";
const { createHash } = require("node:crypto");
const { readFileSync, writeFileSync } = require("node:fs");

const wekufe = process.env.KALKU_WEKUFE ? JSON.parse(readFileSync(process.env.KALKU_WEKUFE, "utf8")) : undefined;

// Asked for its cache key, the wrapper is asked for the file whether or not
// the transform is cached: that is where the wekufe is known to be loaded.
function served(text, file) {
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
  transform: [string, string, unknown][];
}

export class Jest implements Runner {
  readonly name = "jest" as const;
  private transform = "";

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
  }

  async run(selection: Selection | "all", wekufe: Wekufe | undefined, signal?: AbortSignal): Promise<RunResult> {
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
    const env = { ...this.ctx.env };
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
