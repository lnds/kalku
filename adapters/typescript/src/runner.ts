// What this kalku asks of a test runner, whichever it is: run some tests,
// with or without a wekufe served in place of one file, and say what each
// test came to. Nothing a runner does writes into the project: the wekufe
// is handed to the runner in memory, and its caches live in the reni.

import { existsSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import { type FileResult, readReport } from "./results";
import { run } from "./process";

/** The tests to run: whole files, or only the tests with these names in them. */
export interface Selection {
  files: string[];
  /** Each test's titles, outermost first; absent to run every test in the files. */
  names?: string[][];
}

/**
 * A file's text with the wekufe in it, served in place of what is on disk.
 * `files` is every spelling of the file's path: a runner that knows it by
 * the other one would load the original, and every wekufe would survive.
 */
export interface Wekufe {
  files: string[];
  text: string;
  /** A file the runner writes when it loads the wekufe, which says it was loaded. */
  served: string;
}

export function wekufeOf(file: string, text: string, served: string): Wekufe {
  let real = file;
  try {
    real = realpathSync(file);
  } catch {
    // A file that cannot be resolved is known by the one name.
  }
  return { files: real === file ? [file] : [file, real], text, served };
}

export interface RunResult {
  files: FileResult[];
  /** Set when the runner itself failed and wrote no report. */
  failed?: string;
}

export class PrepareError extends Error {}

export interface Runner {
  readonly name: "vitest" | "jest";
  readonly version: string;
  prepare(signal?: AbortSignal): Promise<void>;
  run(selection: Selection | "all", wekufe: Wekufe | undefined, signal?: AbortSignal): Promise<RunResult>;
  differences(): string[];
}

export interface Context {
  root: string;
  /** This worker's own directory in the reni. */
  work: string;
  env: NodeJS.ProcessEnv;
}

/** The version a package installed in the project says it is, or undefined. */
export function installed(
  root: string,
  pkg: string,
): { dir: string; version: string; json: Record<string, unknown> } | undefined {
  try {
    const file = createRequire(path.join(root, "package.json")).resolve(`${pkg}/package.json`);
    const json = JSON.parse(readFileSync(file, "utf8")) as Record<string, unknown>;
    return { dir: path.dirname(file), version: String(json["version"] ?? "?"), json };
  } catch {
    return undefined;
  }
}

/**
 * A regex that matches exactly the full names of these tests, as a runner's
 * `-t` reads them: Jest and Vitest 3 join a test's titles with a space,
 * Vitest 5 with ` > `, so either is taken between two titles.
 */
export function namePattern(names: string[][]): string {
  const escape = (s: string): string => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const each = names.map((n) => n.map(escape).join("(?: | > )"));
  return `^(?:${each.join("|")})$`;
}

/** Runs a runner's command and reads the report it was told to write. */
export async function reported(
  ctx: Context,
  args: string[],
  out: string,
  env: NodeJS.ProcessEnv,
  signal?: AbortSignal,
): Promise<RunResult> {
  rmSync(out, { force: true });
  const ran = await run(process.execPath, args, { cwd: ctx.root, env, ...(signal ? { signal } : {}) });
  if (!existsSync(out)) {
    const how = ran.signal !== null ? `was ended by ${ran.signal}` : `exited with status ${ran.code}`;
    return {
      files: [],
      failed: `the test runner ${how} and wrote no report${ran.said === "" ? "" : `:\n${ran.said}`}`,
    };
  }
  try {
    return { files: readReport(out) };
  } catch (e) {
    return { files: [], failed: `the test runner's report could not be read: ${(e as Error).message}` };
  }
}

/** Writes a file into this worker's directory in the reni, and says where. */
export function asset(ctx: Context, name: string, text: string): string {
  mkdirSync(ctx.work, { recursive: true });
  const file = path.join(ctx.work, name);
  writeFileSync(file, text);
  return file;
}
