import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { test } from "node:test";
import { type Line, LineReader } from "../src/framing";
import * as wire from "../src/protocol";
import { FIXTURES } from "./here";

function lines(name: string): string[] {
  return readFileSync(path.join(FIXTURES, name), "utf8").trimEnd().split("\n");
}

// The fixtures belong to the repository, outside this package. Measured on a
// copy they are not there: that is a skip, except on CI.
const there = existsSync(FIXTURES) || process.env["CI"] !== undefined;

test("every request a kalku can be sent decodes", { skip: !there }, () => {
  let seen = 0;
  for (const f of readdirSync(path.join(FIXTURES, "kalku/requests"))) {
    for (const l of lines(`kalku/requests/${f}`)) {
      seen++;
      assert.doesNotThrow(() => wire.decode(l), `${f}: ${l}`);
    }
  }
  assert.ok(seen >= 10, `only ${seen} request fixtures were read`);
});

test("a malformed line is refused with the kind the fixture names", { skip: !there }, () => {
  for (const entry of lines("invalid/kalku_requests.ndjson")) {
    const { expect, line } = JSON.parse(entry) as { expect: string; line: string };
    assert.throws(
      () => wire.decode(line),
      (e: unknown) => e instanceof wire.DecodeError && e.kind === expect,
      `${line} should be ${expect}`,
    );
  }
});

test("one oversized message costs only itself, and the line after it is read", { skip: !there }, () => {
  for (const entry of lines("invalid/framing.ndjson")) {
    const { expect, max, input } = JSON.parse(entry) as { expect: string; max: number; input: string };
    const reader = new LineReader(max);
    const bytes = Buffer.from(input, "utf8");
    const got: Line[] = [];
    for (let i = 0; i < bytes.length; i += 5) got.push(...reader.feed(bytes.subarray(i, i + 5)));
    got.push(...reader.end());
    assert.equal(
      got.some((l) => l.tooLong),
      expect === "line_too_long",
      entry,
    );
    const last = got[got.length - 1]!;
    assert.ok(!last.tooLong && last.text.includes('"id":8'), `${entry}: the last line was not read`);
  }
});

test("a site read from the wire is written back byte for byte", { skip: !there }, () => {
  for (const l of lines("shapes/site.ndjson")) {
    const r = wire.decode(`{"type":"cast","id":1,"wekufe":"w","tests":[],"site":${l}}`);
    assert.equal(r.type, "cast");
    if (r.type === "cast") assert.equal(wire.siteJson(r.cast.site), l);
  }
});

test("what this kalku says is the fixtures, byte for byte", { skip: !there }, () => {
  const site: wire.Site = {
    site_id: "c1f37a90e2b4",
    file: "lib/my_app/parser.ex",
    enclosing: "MyApp.Parser.next_token/2",
    ordinal: 1,
    start: { line: 42, col: 7, byte: 1180 },
    end: { line: 42, col: 9, byte: 1182 },
    spell: "compare",
    original: ">=",
    replacement: ">",
    reload: "module",
  };
  const timed = [{ test: "test/my_app/parser_test.exs:18", file: "test/my_app/parser_test.exs", duration_ms: 12 }];
  const said: Record<string, string[]> = {
    error: [
      wire.error(1, "protocol_mismatch", "kalku speaks protocol 2, kaikai side speaks 1", true),
      wire.error(5, "unknown_test", "no test test/my_app/parser_test.exs:99", false),
      wire.error(11, "bad_request", "unknown_type: frobnicate", false),
    ],
    bye: [wire.bye(7)],
    aborted: [wire.aborted(9, 5, true), wire.aborted(9, 5, false)],
    reset_done: [wire.resetDone(10, true, 140)],
    prepared: [wire.prepared(2, 41200, 312)],
    progress: [wire.progress(3, 120, 480)],
    sites_found: [wire.sitesFound(4, [], [])],
    cast_done: [
      wire.castDone(
        9,
        "7c2f0b9e41d6",
        { kind: "crashed", message: "80 of 83 selected test(s) ran: the run exited with status 1" },
        912,
      ),
    ],
    baseline_done: [
      wire.baselineDone(
        3,
        18300,
        timed,
        { inline: [{ file: "lib/my_app/parser.ex", line: 42, tests: ["test/my_app/parser_test.exs:18"] }] },
        [],
        undefined,
        undefined,
      ),
      wire.baselineDone(3, 18300, timed, { packedPath: "/tmp/kalku/my_app/coverage.json" }, [], undefined, undefined),
      wire.baselineDone(
        3,
        9100,
        timed,
        {},
        [
          {
            test: "test/my_app/parser_test.exs:18",
            message: 'Assertion with == failed\ncode: assert next_token("", 0) == :eof',
          },
        ],
        ["the build is in /tmp/kalku/my_app/build, not in _build"],
        undefined,
      ),
      wire.baselineDone(
        3,
        4100,
        [
          {
            test: "test/my_app/lexer_test.exs:5",
            file: "test/my_app/lexer_test.exs",
            duration_ms: 9,
            fingerprint: "51be0c3a9d24",
          },
        ],
        { inline: [{ file: "lib/my_app/lexer.ex", line: 12, tests: ["test/my_app/lexer_test.exs:5"] }] },
        [],
        undefined,
        ["test/my_app/parser_test.exs:18"],
      ),
    ],
  };
  for (const [name, messages] of Object.entries(said)) {
    const fixture = lines(`kalku/replies/${name}.ndjson`);
    for (const m of messages) assert.ok(fixture.includes(m), `${name} is not in the fixtures:\n  ${m}`);
  }
  assert.equal(wire.siteJson(site), lines("shapes/site.ndjson")[0]);
});

test("a ready reply is in canonical order", () => {
  assert.equal(
    wire.ready(1, "0.18.2", "Node 22.4.0, Vitest 3.2.4", ["cast", "abort"]),
    '{"type":"ready","id":1,"protocol":1,"language":"typescript","adapter":"0.18.2","runtime":"Node 22.4.0, Vitest 3.2.4",' +
      '"spells":["arm","compare","connect","negate","literal","call"],"capabilities":["cast","abort"]}',
  );
});

test("strings are escaped only where JSON requires", () => {
  assert.equal(
    wire.error(1, "c", '¡ñandú! <a&b> "q" \\ \n\t\u0001', false),
    '{"type":"error","id":1,"code":"c","message":"¡ñandú! <a&b> \\"q\\" \\\\ \\n\\t\\u0001","fatal":false}',
  );
});

test("a baseline reads what it may reuse, and refuses an entry without a fingerprint", () => {
  const r = wire.decode('{"type":"baseline","id":3,"reuse":[{"test":"a.test.ts:a","fingerprint":"f1"}]}');
  assert.ok(r.type === "baseline" && r.reuse?.get("a.test.ts:a") === "f1");
  const none = wire.decode('{"type":"baseline","id":3}');
  assert.ok(none.type === "baseline" && none.reuse === undefined);
  assert.throws(() => wire.decode('{"type":"baseline","id":3,"reuse":[{"test":"a.test.ts:a"}]}'), wire.DecodeError);
});
