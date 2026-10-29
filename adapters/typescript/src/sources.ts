// What a project says its sources are. Its `tsconfig.json` names them, with
// `include`, `exclude`, `files` and the projects it references; a benchmark,
// an example or a script it leaves out is not the project's code, and every
// wekufe there would be one no test was meant to reach.

import path from "node:path";
import ts from "typescript";

/**
 * The files the `tsconfig.json` at the root includes, JavaScript as well, as
 * paths relative to the root; undefined where it says nothing to go by: no
 * config at the root, one TypeScript cannot read, or one that names no file.
 */
export function declaredSources(root: string): Set<string> | undefined {
  const files = new Set<string>();
  const seen = new Set<string>();
  const visit = (config: string): boolean => {
    if (seen.has(config)) return true;
    seen.add(config);
    const read = ts.readConfigFile(config, ts.sys.readFile);
    if (read.error !== undefined) return false;
    // `allowJs` only widens what the patterns match to JavaScript, which the kalku searches too.
    const json = { ...read.config, compilerOptions: { ...read.config.compilerOptions, allowJs: true } };
    const parsed = ts.parseJsonConfigFileContent(json, ts.sys, path.dirname(config), undefined, config);
    for (const f of parsed.fileNames) files.add(path.relative(root, f).split(path.sep).join("/"));
    return (parsed.projectReferences ?? []).every((r) => visit(ts.resolveProjectReferencePath(r)));
  };
  const config = path.join(root, "tsconfig.json");
  if (!ts.sys.fileExists(config) || !visit(config) || files.size === 0) return undefined;
  return files;
}
