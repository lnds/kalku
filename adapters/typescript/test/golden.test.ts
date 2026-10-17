import assert from "node:assert/strict";
import { readFileSync, readdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { SPELLS, siteJson } from "../src/protocol";
import { findSites } from "../src/sites";
import { TESTDATA } from "./here";

// The sites of every fixture, against a golden file beside it. A golden is
// only as good as its review: regenerate with `UPDATE_GOLDEN=1 npm test`,
// then read the diff.
test("every fixture's sites are its golden's", () => {
  const root = path.join(TESTDATA, "sites");
  const fixtures = readdirSync(root).flatMap((dir) =>
    readdirSync(path.join(root, dir))
      .filter((f) => !f.endsWith(".ndjson"))
      .map((f) => `${dir}/${f}`),
  );
  assert.ok(fixtures.length >= 8, `the fixtures went missing: ${fixtures}`);
  for (const name of fixtures) {
    const text = readFileSync(path.join(root, name), "utf8");
    const said = findSites(name, text, SPELLS, ["log.*", "console.log"])
      .map((s) => siteJson(s) + "\n")
      .join("");
    const golden = path.join(root, name.replace(/\.[^.]+$/, ".sites.ndjson"));
    if (process.env["UPDATE_GOLDEN"]) writeFileSync(golden, said);
    assert.equal(said, readFileSync(golden, "utf8"), `${name}: the sites are not the golden's`);
  }
});
