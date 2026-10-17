// The project a `hello` names: which test runner it uses, the environment
// its tests get, and how a test is named. A test is its file and its titles,
// `test/calc.test.ts:above › is true at the floor`, which is stable for as
// long as the test keeps its place and its name.

import { existsSync, readFileSync, realpathSync, statSync } from "node:fs";
import path from "node:path";
import { Jest } from "./jest";
import type { Context, Runner } from "./runner";
import { Vitest } from "./vitest";

/** Between a test's titles in its id. */
export const SEPARATOR = " › ";

// What a test run gets from the environment the kalku was summoned with;
// the rest is whatever `hello.env` adds. Version managers are kept because a
// test that starts `node` finds it through them.
const INHERITED = [
  "PATH",
  "HOME",
  "USER",
  "TMPDIR",
  "LANG",
  "TZ",
  "SHELL",
  "NODE_OPTIONS",
  "NODE_PATH",
  "NODE_EXTRA_CA_CERTS",
];
const INHERITED_PREFIXES = ["ASDF_", "NVM_", "VOLTA_", "FNM_", "MISE_", "NODENV_"];

export function environment(extra: Record<string, string>): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = {};
  for (const [k, v] of Object.entries(process.env)) {
    if (v !== undefined && (INHERITED.includes(k) || INHERITED_PREFIXES.some((p) => k.startsWith(p)))) env[k] = v;
  }
  env["NO_COLOR"] = "1";
  env["FORCE_COLOR"] = "0";
  return { ...env, ...extra };
}

export class Unsupported extends Error {}

/** The runner the project tests with: the one it depends on, or the one `runner` names. */
export function runnerFor(ctx: Context, wanted: string | undefined): Runner {
  const pkg = readPackage(ctx.root);
  const deps = { ...(pkg["dependencies"] as object), ...(pkg["devDependencies"] as object) } as Record<string, string>;
  let name = wanted;
  if (name === undefined) {
    const has = ["vitest", "jest"].filter((r) => deps[r] !== undefined);
    if (has.length === 1) name = has[0];
    else if (has.length === 2) {
      const script = String((pkg["scripts"] as Record<string, string> | undefined)?.["test"] ?? "");
      name = /\bvitest\b/.test(script) ? "vitest" : /\bjest\b/.test(script) ? "jest" : undefined;
      if (name === undefined) {
        throw new Unsupported(
          "the project depends on both Vitest and Jest and its test script names neither: set `runner` under [typescript] in .kalku.toml",
        );
      }
    } else {
      throw new Unsupported("the project depends on neither Vitest nor Jest, the test runners this kalku runs");
    }
  }
  const runner = name === "vitest" ? Vitest.find(ctx) : name === "jest" ? Jest.find(ctx) : undefined;
  if (runner === undefined) {
    throw new Unsupported(`${name} is not installed in the project: run its package manager's install first`);
  }
  return runner;
}

function readPackage(root: string): Record<string, unknown> {
  try {
    return JSON.parse(readFileSync(path.join(root, "package.json"), "utf8")) as Record<string, unknown>;
  } catch {
    return {};
  }
}

/** A test's id, from its file and titles. */
export function testId(root: string, file: string, names: string[]): string {
  return `${relative(root, file)}:${names.join(SEPARATOR)}`;
}

/** A path relative to the root, with `/` between its parts, whichever spelling of the root it was given in. */
export function relative(root: string, file: string): string {
  let rel = path.relative(root, file);
  if (rel.startsWith("..")) {
    try {
      rel = path.relative(realpathSync(root), file);
    } catch {
      // The root's own spelling is the best there is.
    }
  }
  return rel.split(path.sep).join("/");
}

/** The file and titles an id names, or undefined when its file is not in the project. */
export function parseId(root: string, id: string): { file: string; names: string[] } | undefined {
  for (let at = id.indexOf(":"); at !== -1; at = id.indexOf(":", at + 1)) {
    const file = path.join(root, id.slice(0, at));
    if (existsSync(file) && statSync(file).isFile()) return { file, names: id.slice(at + 1).split(SEPARATOR) };
  }
  return undefined;
}
