# kalku protocol

Two protocols share one framing:

- **kalku protocol** — the kaikai side ↔ a kalku (language worker). The contract that keeps the core language-agnostic.
- **client protocol** — a client (CLI, editor, CI) ↔ the server.

Terms follow the glossary in `docs/design.md`.

## Framing

- Newline-delimited JSON: one message per line, UTF-8, no embedded newlines.
- Identical over stdio and over a socket. No field depends on the transport.
- A kalku writes only protocol messages to stdout. Anything else — logs, compiler chatter, test output — goes to stderr, which the kaikai side captures for diagnostics.

Every message carries:

| Field | Type | Meaning |
|---|---|---|
| `type` | string | message type |
| `id` | int | request id; a response echoes its request's `id` |

Requests flow from the kaikai side to the kalku; a kalku never initiates. Each request gets exactly one response: its success message or `error`.

The one exception to silence between request and response is `progress`, which a kalku may send any number of times while a long request (`prepare`, `baseline`, `delegate`) is running. It is a notification, not a response:

```json
{"type":"progress","id":3,"done":120,"total":480}
```

Exactly one request is in flight per kalku at a time, with one exception: `abort` may be sent while a `cast` is running.

Large payloads (coverage maps, test lists) may be written to a file inside the reni and referenced by path (`*_path` fields) instead of inlined. The kaikai side chooses via `hello.inline_limit_bytes`.

## Versioning

`hello` carries the protocol version the kaikai side speaks; `ready` carries the kalku's. They must match exactly for major versions. Adding an optional field is compatible; renaming, removing, or changing a field's meaning is a new version.

Current version: **1**.

## Shared shapes

```jsonc
// Position: 1-based line and column, plus 0-based byte offset.
{ "line": 42, "col": 7, "byte": 1180 }

// Span: half-open [start, end).
{ "start": Position, "end": Position }

// Site
{
  "site_id": "c1f3…",            // stable: hash(file hash, span, spell, replacement)
  "file": "lib/my_app/parser.ex", // relative to the project root
  "enclosing": "MyApp.Parser.next_token/2",
  "ordinal": 1,                   // nth occurrence of `original` for this spell within `enclosing`
  "span": Span,
  "spell": "compare",
  "original": ">=",
  "replacement": ">",
  "reload": "module"              // "module" | "dependents"
}

// Test id: opaque to the kaikai side, meaningful to the kalku.
"test/my_app/parser_test.exs:18"
```

`enclosing`, `spell`, `original`, and `ordinal` form the site's semantic key, which `.kalku/equivalent` entries match against; it survives edits elsewhere in the file where line numbers do not.

`reload: "dependents"` tells the kaikai side the site's module is a compile-time dependency of others, so casting it costs a recompile of those modules. The planner uses it for ordering and timeouts.

## kalku protocol

### `hello` → `ready`

First message after summoning.

```json
{"type":"hello","id":1,"protocol":1,"root":"/home/u/my_app","reni":"/home/u/.cache/kalku/9ab2/reni","worker":3,"inline_limit_bytes":1048576,"env":{"MIX_TEST_PARTITION":"3"}}
```

```json
{"type":"ready","id":1,"protocol":1,"language":"elixir","adapter":"0.1.0","runtime":"Elixir 1.18.1 / OTP 27","spells":["arm","compare","connect","negate","literal","call"],"capabilities":["cast","per_test_coverage","hot_load","recompile_dependents","abort","reset","code_hash"]}
```

`capabilities` tells the kaikai side which kind of kalku this is and lets it degrade gracefully:

| Capability | Meaning | Without it |
|---|---|---|
| `cast` | native kalku: answers `sites`, `cast`, `reload` | — |
| `delegate` | driver kalku: answers `delegate` | — |
| `per_test_coverage` | `baseline` reports coverage per test | every wekufe runs the whole suite |
| `hot_load` | a wekufe loads into the running runtime | each cast rebuilds |
| `recompile_dependents` | sites may carry `reload: "dependents"` | — |
| `abort` | a running cast can be stopped in place | a timeout kills the process |
| `reset` | global state can be reset in place | a dirty kalku is recycled |
| `code_hash` | `cast_done` carries `code_hash` and may prove `equivalent` | no trivial compiler equivalence |

A kalku announces exactly one of `cast` or `delegate`.

### `prepare` → `prepared`

Compile the project into the reni and start the test environment.

```json
{"type":"prepare","id":2}
```

```json
{"type":"prepared","id":2,"duration_ms":41200,"modules":312}
```

### `baseline` → `baseline_done`

Run the full suite with per-test coverage.

```json
{"type":"baseline","id":3}
```

```json
{"type":"baseline_done","id":3,"status":"green","duration_ms":18300,
 "tests":[{"test":"test/my_app/parser_test.exs:18","file":"test/my_app/parser_test.exs","duration_ms":12}],
 "coverage_path":"/home/u/.cache/kalku/9ab2/reni/baseline/coverage.ndjson",
 "failures":[]}
```

Coverage entries, inline (`coverage`) or one per line in `coverage_path`:

```json
{"file":"lib/my_app/parser.ex","line":42,"tests":["test/my_app/parser_test.exs:18"]}
```

`status: "red"` lists the failing tests in `failures`; the kaikai side aborts the run.

### `sites` → `sites_found`

Find sites in the given files, for the given spells.

```json
{"type":"sites","id":4,"files":["lib/my_app/parser.ex"],"spells":["arm","compare"]}
```

```json
{"type":"sites_found","id":4,"sites":[Site, …],"skipped":[{"file":"lib/my_app/gen.ex","reason":"parse_error","message":"…"}]}
```

A file the kalku cannot parse is reported in `skipped`, not as a request `error`.

### `cast` → `cast_done`

Cast one wekufe: splice the site, load it, run the listed tests, restore.

```json
{"type":"cast","id":5,"wekufe":"c1f3…","site":Site,"tests":["test/my_app/parser_test.exs:18"]}
```

```json
{"type":"cast_done","id":5,"wekufe":"c1f3…","outcome":"killed","killed_by":"test/my_app/parser_test.exs:18","code_hash":"77e0…","duration_ms":37,"dirty":false}
```

```json
{"type":"cast_done","id":6,"wekufe":"a4d9…","outcome":"equivalent","evidence":"identical_bytecode","code_hash":"19bc…","duration_ms":4,"dirty":false}
```

| `outcome` | Meaning |
|---|---|
| `killed` | a listed test failed; `killed_by` names it |
| `survived` | every listed test passed |
| `compile_error` | the spliced module did not compile; `message` holds the diagnostic |
| `equivalent` | the wekufe compiled to the same code as the original; no test was run. Requires `evidence` |

`evidence` values: `identical_bytecode`. A kalku reports `equivalent` only with mechanical evidence; declared equivalents are applied by the kaikai side before a cast is ever sent.

`code_hash` identifies the compiled wekufe (present whenever it compiled). The kaikai side uses it to recognise duplicate wekufe reached from different sites.

`timeout` and `crashed` never appear here: the kaikai side detects them from outside (a missing response, a dead process) and records them itself. A native kalku does not enforce its own timeout. The only kalku that report them are drivers, relaying what their framework observed.

`dirty: true` means the kalku could not guarantee its runtime is back to the original state (global state was touched). The kaikai side recycles it before the next cast.

### `abort` → `aborted`

Sent while a `cast` is running, when the kaikai side has decided it timed out. The kalku stops the cast's work inside its runtime and restores the original modules.

```json
{"type":"abort","id":9,"cast":5}
```

```json
{"type":"aborted","id":9,"cast":5,"restored":true}
```

The aborted `cast` gets no `cast_done`; the kaikai side records `timeout`. `restored: false` means the kalku could not restore its runtime; the kaikai side recycles it. If `aborted` does not arrive within the grace period, the kaikai side kills the process.

### `reset` → `reset_done`

Sent after a `cast_done` with `dirty: true`. The kalku resets global state in place (for Elixir: restart the project's applications, clear its ETS tables).

```json
{"type":"reset","id":10}
```

```json
{"type":"reset_done","id":10,"clean":true,"duration_ms":140}
```

`clean: false` means the reset did not reach a known state; the kaikai side recycles the kalku.

### `reload` → `reloaded`

Source files changed on disk; recompile and load them.

```json
{"type":"reload","id":6,"files":["lib/my_app/parser.ex"]}
```

```json
{"type":"reloaded","id":6,"modules":["MyApp.Parser"],"dependents":["MyApp.Lexer"],"duration_ms":820}
```

### `delegate` → `delegated` (driver kalku)

A driver kalku answers `delegate` instead of `sites`/`cast`: it runs the underlying framework over the scope and returns normalised outcomes.

```json
{"type":"delegate","id":4,"scope":{"since":"main"},"spells":null}
```

```json
{"type":"delegated","id":4,"tool":"stryker","tool_version":"8.2.0","outcomes_path":"/home/u/.cache/kalku/9ab2/reni/delegate/4.ndjson","duration_ms":312000}
```

One outcome per line in `outcomes_path` (or inline in `outcomes`):

```json
{"file":"src/cart.ts","enclosing":null,"span":Span,"spell":"compare","tool_mutator":"EqualityOperator","original":"<=","replacement":"<","outcome":"timeout","tool_status":"Timeout","killed_by":null}
```

- `outcome` is the kalku outcome after normalisation (table in `docs/design.md`, *Normalisation*); `tool_status` is the framework's own status, kept for transparency.
- `spell` is the closest shared spell, or `"foreign"` when none fits; `tool_mutator` always names the framework's operator.
- `enclosing` is `null` when the framework does not expose it; declared equivalents for such outcomes fall back to a line-based key.
- A driver never reports a score.

### `shutdown` → `bye`

```json
{"type":"shutdown","id":7}
```

```json
{"type":"bye","id":7}
```

After `bye` the kalku exits with status 0. A kalku that does not exit within a grace period is killed.

### `error`

Any request may be answered with:

```json
{"type":"error","id":5,"code":"load_failed","message":"…","fatal":false}
```

`fatal: true` means the kalku cannot serve further requests; the kaikai side banishes it.

| `code` | Meaning |
|---|---|
| `protocol_mismatch` | versions differ (answer to `hello`) |
| `prepare_failed` | the project did not compile |
| `unknown_test` | a test id is not known to this kalku |
| `load_failed` | the original module could not be restored |
| `bad_request` | malformed or unexpected message |

## Client protocol

Over the server's Unix socket. Requests from the client; the server answers with a stream of events ending in one terminal event (`report` or `error`).

### `run`

```json
{"type":"run","id":1,"root":"/home/u/my_app","scope":{"since":"main"},"format":"json"}
```

`scope` is one of `{"all":true}`, `{"since":"<git ref>"}`, `{"files":["…"]}`. An optional `"shard":{"index":2,"count":4}` restricts the run to a deterministic partition of the planned wekufe.

Events:

```json
{"type":"phase","id":1,"phase":"baseline"}
{"type":"progress","id":1,"done":120,"total":480}
{"type":"outcome","id":1,"wekufe":"c1f3…","site":Site,"outcome":"survived","duration_ms":41}
{"type":"report","id":1,"score":0.83,"counts":{"killed":380,"survived":78,"timeout":6,"no_coverage":14,"compile_error":2,"crashed":0,"equivalent":{"bytecode":9,"declared":5}},"survivors_on_changed_lines":3,"exit":1}
```

`exit` is the exit code a CLI client should return (see *Running in CI* in `docs/design.md`).

### `status`, `cancel`

```json
{"type":"status","id":2}
{"type":"sessions","id":2,"sessions":[{"root":"/home/u/my_app","state":"ready","kalku":8,"idle_s":212}]}
```

```json
{"type":"cancel","id":3,"run":1}
{"type":"cancelled","id":3,"run":1}
```

A cancelled run keeps the outcomes already recorded in the cache.

## Fixtures

Every message type has example payloads under `docs/protocol/fixtures/`, validated by the kaikai side's tests and by each adapter's tests. A message change without a fixture change does not land.
