// Bundles the kalku into one file that runs on the project's own Node, with
// the TypeScript parser inside it: the kalku never depends on what the
// project installed.
import { build } from "esbuild";
import { chmodSync } from "node:fs";

const out = "bin/kalku-typescript";
await build({
  entryPoints: ["src/main.ts"],
  bundle: true,
  platform: "node",
  target: "node20",
  format: "cjs",
  minify: true,
  legalComments: "none",
  banner: { js: "#!/usr/bin/env node" },
  outfile: out,
  logLevel: "warning",
});
chmodSync(out, 0o755);
