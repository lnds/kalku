import assert from "node:assert/strict";
import path from "node:path";
import { test } from "node:test";
import { search } from "../src/service";
import { TESTDATA } from "./here";

const ROOT = path.join(TESTDATA, "projects", "jest");
const ASKED = { files: [], spells: [], exclude_calls: [] };

// A Jest config at the root of a project is matched as a source file by a run
// of the whole project, and no run could ever measure it.
test("a file that is not the project's code is left out by design, and one that cannot be read is not", () => {
  const said = (file: string) => search(ROOT, file, ASKED);
  assert.deepEqual(
    ["jest.config.cjs", "test/calc.test.ts", "node_modules/a/index.ts", "README.md"].map((f) => said(f)),
    [
      { file: "jest.config.cjs", reason: "config", by_design: true },
      { file: "test/calc.test.ts", reason: "test_file", by_design: true },
      { file: "node_modules/a/index.ts", reason: "dependency", by_design: true },
      { file: "README.md", reason: "not_typescript", by_design: true },
    ].map((s) => ({ ...s, message: (said(s.file) as { message: string }).message })),
  );
  const gone = said("src/gone.ts");
  assert.equal("reason" in gone && gone.reason, "unreadable");
  assert.equal("by_design" in gone, false);
});
