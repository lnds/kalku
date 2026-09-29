# kalku protocol

Two protocols share one framing:

- **kalku protocol** — the kaikai side ↔ a kalku (language worker). The contract that keeps the core language-agnostic.
- **client protocol** — a client (CLI, editor, CI, the MCP server for coding agents) ↔ the server.

Terms follow the glossary in `docs/design.md`. Every message below has fixtures under `docs/protocol/fixtures/` (see *Fixtures*); where this document and a fixture disagree, both are wrong until fixed together.

## Framing

- Newline-delimited JSON: one message per line, UTF-8, terminated by `\n`, no embedded newlines.
- Identical over stdio and over a socket. No field depends on the transport.
- **Maximum line length: 4 MiB** (4 194 304 bytes, excluding the `\n`). A receiver that meets a longer line discards bytes up to the next `\n` without buffering them and treats the line as a `line_too_long` protocol error. Large payloads never need long lines: they go to files (see *Large payloads*).
- A kalku writes only protocol messages to stdout. Anything else — logs, compiler chatter, test output — goes to stderr, which the kaikai side captures for diagnostics.

Every message is a JSON object that carries:

| Field | Type | Meaning |
|---|---|---|
| `type` | string | message type |
| `id` | int | request id; a response or event echoes its request's `id` |

### Requests and responses

Requests flow from the kaikai side to the kalku; a kalku never initiates. Each request gets exactly one response: its success message or `error`.

The one exception to silence between request and response is `progress`, which a kalku may send any number of times while a long request (`prepare`, `baseline`, `delegate`) is running. It is a notification, not a response.

Exactly one request is in flight per kalku at a time, with one exception: `abort` may be sent while a `cast` is running.

In the client protocol, the server answers a request with a stream of events ending in one terminal event (named per request below) or `error`.

### Large payloads

Coverage maps and delegated outcomes may be written to a file inside the reni and referenced by path (`*_path` fields) instead of inlined. The kaikai side sets the threshold with `hello.inline_limit_bytes`; a payload above it goes to a file. Such files are NDJSON, one entry per line, in the entry shape the inline field would use.

## Encoding

**Canonical form**, which every sender produces:

- `type`, then `id`, then the fields in the order the tables below list them;
- optional fields that are absent are **omitted**, never written as `null`;
- no insignificant whitespace;
- strings in raw UTF-8; only `"`, `\`, and control characters are escaped;
- integers as integers; reals in their shortest round-tripping form.

**Decoding**, which every receiver accepts:

- fields in any order;
- an optional field that is missing or `null` is absent; a required field that is missing or `null` is an error;
- **unknown fields are ignored** (forward compatibility);
- an **unknown `type` is an error**;
- an unknown value in a closed set (a spell, an outcome, …) is an error — except capabilities, where unknown values are ignored so a newer kalku can announce more.

### Protocol errors

A message a receiver cannot accept is a protocol error of one of these kinds. A kalku answers it with `error` / `bad_request`; the kaikai side treats a kalku's protocol error as fatal for that kalku.

| Kind | Meaning |
|---|---|
| `line_too_long` | the line exceeds the maximum length |
| `not_json` | the line is not valid JSON |
| `not_object` | the line is JSON but not an object |
| `missing_type` | no string `type` field |
| `unknown_type` | `type` names no message of this protocol and direction |
| `missing_id` | no integer `id` field |
| `bad_field` | a field is missing, has the wrong shape, or holds an unknown closed-set value; the error names its path |
| `invalid` | every field is well-formed but the combination breaks a rule stated below |

## Versioning

`hello` carries the protocol version the kaikai side speaks; `ready` carries the kalku's. They must match exactly. Adding an optional field is compatible; renaming, removing, or changing a field's meaning is a new version.

Current version: **1**.

## Shared shapes

Field tables list fields in canonical order. `?` marks an optional field.

### Position

| Field | Type | Meaning |
|---|---|---|
| `line` | int | 1-based line |
| `col` | int | 1-based column, in characters |
| `byte` | int | 0-based byte offset in the file |

### Span

Half-open `[start, end)`.

| Field | Type | Meaning |
|---|---|---|
| `start` | Position | |
| `end`? | Position | absent only from a kalku that cannot determine it |

### Site

| Field | Type | Meaning |
|---|---|---|
| `site_id` | string | stable: hash of file content hash, span, spell, replacement |
| `file` | string | relative to the project root |
| `enclosing`? | string | enclosing declaration, e.g. `MyApp.Parser.next_token/2` |
| `ordinal`? | int | 1-based occurrence of `(spell, original)` within `enclosing` |
| `span` | Span | |
| `spell` | Spell | |
| `original`? | string | exact source text of the span |
| `replacement` | string | text written in its place; `""` deletes |
| `reload` | `"module"` \| `"dependents"` | `dependents`: the module is a compile-time dependency of others, so a cast recompiles them too |

`enclosing`, `spell`, `original`, and `ordinal` form the site's semantic key, which `.kalku/equivalent` entries match against; it survives edits elsewhere in the file where line numbers do not. A site without them falls back to a line-based key.

```json
{"site_id":"c1f37a90e2b4","file":"lib/my_app/parser.ex","enclosing":"MyApp.Parser.next_token/2","ordinal":1,"span":{"start":{"line":42,"col":7,"byte":1180},"end":{"line":42,"col":9,"byte":1182}},"spell":"compare","original":">=","replacement":">","reload":"module"}
```

### Closed sets

| Set | Values |
|---|---|
| Spell | `arm` `compare` `connect` `negate` `literal` `call` `await` `supervise` `foreign` (drivers only) |
| Outcome | `killed` `survived` `timeout` `no_coverage` `compile_error` `crashed` `nondeterministic` `equivalent` |
| Capability | `cast` `delegate` `per_test_coverage` `hot_load` `recompile_dependents` `abort` `reset` `code_hash` — unknown values ignored |
| Format | `human` `json` `github` `agent` |
| Phase | `prepare` `baseline` `plan` `cast` `report` |
| Session state | `cold` `preparing` `baseline` `ready` `running` `failed` `closed` |

### Scope

Exactly one of:

```json
{"all":true}
{"since":"main"}
{"files":["lib/my_app/parser.ex"]}
```

A scope with none or more than one key, or `"all":false`, is a malformed field: `bad_field`.

### Test ids

Opaque to the kaikai side, meaningful to the kalku, stable across runs for an unchanged test: `"test/my_app/parser_test.exs:18"`.

## kalku protocol

### `hello` → `ready`

First message after summoning.

| `hello` | Type | Meaning |
|---|---|---|
| `protocol` | int | version the kaikai side speaks |
| `root` | string | absolute project root |
| `reni` | string | absolute path of this project's reni |
| `worker` | int | this kalku's index in the pool |
| `inline_limit_bytes` | int | payloads above this go to files |
| `env` | object of strings | extra environment for the test runtime |

| `ready` | Type | Meaning |
|---|---|---|
| `protocol` | int | version the kalku speaks |
| `language` | string | |
| `adapter` | string | the kalku implementation's version |
| `runtime` | string | the target runtime, human-readable |
| `spells` | [Spell] | spells this kalku can cast |
| `capabilities` | [Capability] | see below |

```json
{"type":"hello","id":1,"protocol":1,"root":"/home/u/my_app","reni":"/home/u/.cache/kalku/9ab2/reni","worker":3,"inline_limit_bytes":1048576,"env":{"MIX_TEST_PARTITION":"3"}}
{"type":"ready","id":1,"protocol":1,"language":"elixir","adapter":"0.1.0","runtime":"Elixir 1.18.1 / OTP 27","spells":["arm","compare","connect","negate","literal","call"],"capabilities":["cast","per_test_coverage","hot_load","recompile_dependents","abort","reset","code_hash"]}
```

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

Rule: `ready` announces **exactly one** of `cast` or `delegate`; otherwise `invalid`.

### `prepare` → `prepared`

Compile the project into the reni and start the test environment. `prepare` has no fields.

| `prepared` | Type | Meaning |
|---|---|---|
| `duration_ms` | int | |
| `modules` | int | modules compiled |

```json
{"type":"prepare","id":2}
{"type":"prepared","id":2,"duration_ms":41200,"modules":312}
```

### `baseline` → `baseline_done`

Run the full suite, with per-test coverage when capable. `baseline` has no fields.

| `baseline_done` | Type | Meaning |
|---|---|---|
| `status` | `"green"` \| `"red"` | |
| `duration_ms` | int | |
| `tests` | [{`test`, `file`, `duration_ms`}] | every test that ran |
| `coverage`? | [{`file`, `line`, `tests`}] | inline coverage |
| `coverage_path`? | string | the same entries, one per line, in a file |
| `failures` | [{`test`, `message`}] | empty when green |

Rules: at most one of `coverage` / `coverage_path` (neither when the kalku lacks `per_test_coverage`); `status: "red"` requires at least one failure, `"green"` requires none. `red` aborts the run.

```json
{"type":"baseline","id":3}
{"type":"baseline_done","id":3,"status":"green","duration_ms":18300,"tests":[{"test":"test/my_app/parser_test.exs:18","file":"test/my_app/parser_test.exs","duration_ms":12}],"coverage":[{"file":"lib/my_app/parser.ex","line":42,"tests":["test/my_app/parser_test.exs:18"]}],"failures":[]}
```

### `progress` (notification)

| Field | Type |
|---|---|
| `done` | int |
| `total` | int |

```json
{"type":"progress","id":3,"done":120,"total":480}
```

### `sites` → `sites_found`

| `sites` | Type | Meaning |
|---|---|---|
| `files` | [string] | files to search, relative to the root |
| `spells` | [Spell] | spells to propose |
| `exclude_calls` | [string] | call patterns whose arguments and bodies get no sites, e.g. `Logger.*` |

| `sites_found` | Type | Meaning |
|---|---|---|
| `sites` | [Site] | |
| `skipped` | [{`file`, `reason`, `message`}] | files the kalku could not read or parse; `reason` is e.g. `parse_error` |

A file the kalku cannot parse is reported in `skipped`, not as a request `error`.

```json
{"type":"sites","id":4,"files":["lib/my_app/parser.ex"],"spells":["arm","compare"],"exclude_calls":["Logger.*"]}
```

### `cast` → `cast_done`

Cast one wekufe: splice the site, load it, run the listed tests, restore.

| `cast` | Type | Meaning |
|---|---|---|
| `wekufe` | string | id; equals the site's `site_id` |
| `site` | Site | |
| `tests` | [test id] | tests to run |

| `cast_done` | Type | Meaning |
|---|---|---|
| `wekufe` | string | |
| `outcome` | `killed` \| `survived` \| `compile_error` \| `equivalent` | |
| `killed_by`? | test id | the failing test, when known |
| `message`? | string | compiler diagnostic for `compile_error` |
| `code_hash`? | string | hash of the compiled wekufe, whenever it compiled and the kalku has `code_hash` |
| `duration_ms` | int | |
| `dirty` | bool | the runtime may not be back to its original state |

`timeout`, `crashed` and `nondeterministic` never appear here: the kaikai side decides them from outside — a missing response, a dead process, or a wekufe whose repeated casts did not agree with each other. A native kalku does not enforce its own timeout, and does not know that it has been asked to cast the same wekufe twice. Any other outcome is `invalid`.

`equivalent` means the wekufe compiled to the same code as the original, so no test ran: identical compiled code is the only mechanical evidence of equivalence, and a kalku never reports equivalence on any other ground. Should another kind of evidence appear, it arrives as a new optional field.

`dirty: true` makes the kaikai side send `reset` before the next cast, and recycle the kalku only if that fails.

```json
{"type":"cast","id":5,"wekufe":"c1f37a90e2b4","site":{"site_id":"c1f37a90e2b4","file":"lib/my_app/parser.ex","enclosing":"MyApp.Parser.next_token/2","ordinal":1,"span":{"start":{"line":42,"col":7,"byte":1180},"end":{"line":42,"col":9,"byte":1182}},"spell":"compare","original":">=","replacement":">","reload":"module"},"tests":["test/my_app/parser_test.exs:18"]}
{"type":"cast_done","id":5,"wekufe":"c1f37a90e2b4","outcome":"killed","killed_by":"test/my_app/parser_test.exs:18","code_hash":"77e0c3d91f2a","duration_ms":37,"dirty":false}
{"type":"cast_done","id":6,"wekufe":"a4d95e11b7c0","outcome":"equivalent","code_hash":"19bc40f2d8a1","duration_ms":4,"dirty":false}
```

### `abort` → `aborted`

Sent while a `cast` is running, when the kaikai side has decided it timed out. The kalku stops the cast's work inside its runtime and restores the original modules.

| `abort` | Type | Meaning |
|---|---|---|
| `cast` | int | `id` of the running `cast` |

| `aborted` | Type | Meaning |
|---|---|---|
| `cast` | int | |
| `restored` | bool | `false`: the kalku could not restore its runtime; the kaikai side recycles it |

The aborted `cast` gets no `cast_done`; the kaikai side records `timeout`. If `aborted` does not arrive within the grace period, the kaikai side kills the process.

```json
{"type":"abort","id":9,"cast":5}
{"type":"aborted","id":9,"cast":5,"restored":true}
```

### `reset` → `reset_done`

Sent after a `cast_done` with `dirty: true`. The kalku resets global state in place (for Elixir: restart the project's applications, clear its ETS tables). `reset` has no fields.

| `reset_done` | Type | Meaning |
|---|---|---|
| `clean` | bool | `false`: the reset did not reach a known state; the kaikai side recycles the kalku |
| `duration_ms` | int | |

```json
{"type":"reset","id":10}
{"type":"reset_done","id":10,"clean":true,"duration_ms":140}
```

### `reload` → `reloaded`

Source files changed on disk; recompile and load them.

| `reload` | Type |
|---|---|
| `files` | [string] |

| `reloaded` | Type | Meaning |
|---|---|---|
| `modules` | [string] | modules reloaded |
| `dependents` | [string] | modules recompiled because they depend on those at compile time |
| `duration_ms` | int | |

```json
{"type":"reload","id":6,"files":["lib/my_app/parser.ex"]}
{"type":"reloaded","id":6,"modules":["MyApp.Parser"],"dependents":["MyApp.Lexer"],"duration_ms":820}
```

### `delegate` → `delegated` (driver kalku)

A driver kalku answers `delegate` instead of `sites`/`cast`: it runs the underlying framework over the scope and returns normalised outcomes.

| `delegate` | Type | Meaning |
|---|---|---|
| `scope` | Scope | |
| `spells`? | [Spell] | absent: every spell the framework has |

| `delegated` | Type | Meaning |
|---|---|---|
| `tool` | string | the framework |
| `tool_version` | string | |
| `outcomes`? | [Delegated outcome] | inline |
| `outcomes_path`? | string | the same entries, one per line, in a file |
| `duration_ms` | int | |

Rule: exactly one of `outcomes` / `outcomes_path`.

**Delegated outcome:**

| Field | Type | Meaning |
|---|---|---|
| `file` | string | |
| `enclosing`? | string | absent when the framework does not expose it |
| `span` | Span | |
| `spell` | Spell | closest shared spell, or `foreign` |
| `tool_mutator` | string | the framework's operator |
| `original`? | string | |
| `replacement`? | string | |
| `outcome` | Outcome | after normalisation (*Normalisation* in `docs/design.md`) |
| `tool_status` | string | the framework's own status |
| `killed_by`? | string | |

A driver never reports a score. Declared equivalents for outcomes without `enclosing` fall back to a line-based key.

```json
{"type":"delegate","id":4,"scope":{"since":"main"}}
{"type":"delegated","id":4,"tool":"stryker","tool_version":"8.2.0","outcomes_path":"/home/u/.cache/kalku/9ab2/reni/delegate/4.ndjson","duration_ms":312000}
```

### `shutdown` → `bye`

No fields either way. After `bye` the kalku exits with status 0. A kalku that does not exit within a grace period is killed.

```json
{"type":"shutdown","id":7}
{"type":"bye","id":7}
```

### `error`

Any request may be answered with `error`.

| Field | Type | Meaning |
|---|---|---|
| `code` | string | see below |
| `message` | string | human-readable |
| `fatal` | bool | the kalku cannot serve further requests; the kaikai side banishes it |

| `code` | Meaning |
|---|---|
| `protocol_mismatch` | versions differ (answer to `hello`) |
| `prepare_failed` | the project did not compile |
| `unknown_test` | a test id is not known to this kalku |
| `load_failed` | the original module could not be restored |
| `bad_request` | a protocol error in the request, or a request this kalku does not serve |

```json
{"type":"error","id":5,"code":"load_failed","message":"could not restore MyApp.Parser","fatal":false}
```

## Client protocol

Over the server's Unix socket.

### `run`

| `run` | Type | Meaning |
|---|---|---|
| `root` | string | absolute project root |
| `scope` | Scope | |
| `format` | Format | how the client will render; the server shapes `hint`s and summaries accordingly |
| `shard`? | {`index`, `count`} | 1-based `index` of `count`; restricts the run to a deterministic partition of the planned wekufe |

Rule: `1 ≤ index ≤ count`.

Events, terminal event `report`:

| `phase` | Type |
|---|---|
| `phase` | Phase |

| `progress` | Type | Meaning |
|---|---|---|
| `done` | int | wekufe cast so far |
| `total` | int | wekufe this run will cast |
| `estimate_ms`? | int | how long the casting is likely to take; sent once, before the first wekufe |

`estimate_ms` is the baseline's own test durations — what the tests that judge each planned wekufe took — shared out over the run's workers. It counts the tests and nothing else, so it is a floor rather than a promise: loading a wekufe into a warm kalku costs something the kaikai side cannot know without measuring it. A client with no terminal to watch reads the number and can show it, or refuse the run, instead of waiting out a surprise.

Unlike the kalku protocol's `progress`, which reports how far one long request has got, this one is about the casting.

| `outcome` | Type | Meaning |
|---|---|---|
| `wekufe` | string | |
| `site`? | Site | absent only with `gone` |
| `outcome` | Outcome \| `"gone"` | `gone`: the wekufe's site no longer exists (see `cast`) |
| `covering_tests`? | [test id] | |
| `hint`? | string | present on `survived`: a fixed template per spell, never model-generated |
| `duration_ms`? | int | |

| `report` | Type | Meaning |
|---|---|---|
| `score`? | real | `killed / (killed + survived)`; absent when that denominator is 0 |
| `counts` | Counts | |
| `survivors_on_changed_lines` | int | |
| `suppression_changes` | Suppression changes | |
| `exit` | int | the exit code a CLI client should return (*Running in CI* in `docs/design.md`) |

**Counts:** `killed`, `survived`, `timeout`, `no_coverage`, `compile_error`, `crashed`, `nondeterministic` (ints), `equivalent` ({`bytecode`, `declared`} ints).

**Suppression changes:** `equivalent_added` (int), `exclude_added` ([string]), `exclude_calls_added` ([string]) — suppressions the scope's diff adds, so every client can show them. Zero and empty when nothing changed.

```json
{"type":"run","id":1,"root":"/home/u/my_app","scope":{"since":"main"},"format":"json"}
{"type":"phase","id":1,"phase":"baseline"}
{"type":"phase","id":1,"phase":"cast"}
{"type":"progress","id":1,"done":0,"total":480,"estimate_ms":540000}
{"type":"progress","id":1,"done":120,"total":480}
{"type":"outcome","id":1,"wekufe":"c1f37a90e2b4","site":{"site_id":"c1f37a90e2b4","file":"lib/my_app/parser.ex","enclosing":"MyApp.Parser.next_token/2","ordinal":1,"span":{"start":{"line":42,"col":7,"byte":1180},"end":{"line":42,"col":9,"byte":1182}},"spell":"compare","original":">=","replacement":">","reload":"module"},"outcome":"survived","covering_tests":["test/my_app/parser_test.exs:18"],"hint":"No test tells i >= 0 apart from i > 0. Add a case at the boundary where they are equal.","duration_ms":41}
{"type":"report","id":1,"score":0.83,"counts":{"killed":380,"survived":78,"timeout":6,"no_coverage":14,"compile_error":2,"crashed":0,"nondeterministic":0,"equivalent":{"bytecode":9,"declared":5}},"survivors_on_changed_lines":3,"suppression_changes":{"equivalent_added":2,"exclude_added":[],"exclude_calls_added":["MyApp.Metrics.*"]},"exit":1}
```

### `cast`

Re-cast specific wekufe, typically after a test was written to kill them. The server reloads changed files, refreshes coverage for changed tests, and first runs changed tests against the original code.

| `cast` | Type |
|---|---|
| `root` | string |
| `wekufe` | [string] |

Events: `phase`, one `outcome` per wekufe (`gone` for a wekufe whose site no longer exists), `test_rejected` for each changed test that fails on the original, then a terminal `report` covering only those wekufe. `gone` is a client-protocol status, never a run outcome, and never enters a score.

| `test_rejected` | Type | Meaning |
|---|---|---|
| `test` | test id | |
| `reason` | string | `fails_on_original` |
| `message`? | string | the failure |

```json
{"type":"cast","id":4,"root":"/home/u/my_app","wekufe":["c1f37a90e2b4","a4d95e11b7c0"]}
{"type":"test_rejected","id":4,"test":"test/my_app/parser_test.exs:25","reason":"fails_on_original","message":"expected :ok, got :error"}
```

### `show`

| `show` | Type |
|---|---|
| `root` | string |
| `wekufe` | string |

Terminal event `wekufe`:

| `wekufe` | Type | Meaning |
|---|---|---|
| `wekufe` | string | |
| `site` | Site | |
| `outcome` | Outcome | latest |
| `covering_tests` | [test id] | |
| `hint`? | string | |
| `context` | [string] | source lines around the site |
| `history` | [{`at`, `outcome`}] | `at` is an RFC 3339 UTC timestamp |

### `propose_equivalent`

Appends a proposal to `.kalku/equivalent.proposed`; never touches `.kalku/equivalent`.

| `propose_equivalent` | Type |
|---|---|
| `root` | string |
| `wekufe` | string |
| `reason` | string (non-empty) |

Terminal event `proposed`: `entry` (string), the line written, in `.kalku/equivalent` syntax.

```json
{"type":"propose_equivalent","id":6,"root":"/home/u/my_app","wekufe":"c1f37a90e2b4","reason":"index is always even here; > and >= agree"}
{"type":"proposed","id":6,"entry":"lib/my_app/parser.ex  MyApp.Parser.next_token/2  compare  \">=\"  #1   # index is always even here; > and >= agree"}
```

### `status`, `cancel`

`status` has no fields. Terminal event `sessions`: `sessions`, a list of {`root`, `state` (Session state), `kalku` (int, live kalku), `idle_s` (int)}.

`cancel`: `run` (int, the `id` of the run to cancel). Terminal event `cancelled`: `run`. A cancelled run keeps the outcomes already recorded in the cache.

```json
{"type":"status","id":2}
{"type":"sessions","id":2,"sessions":[{"root":"/home/u/my_app","state":"ready","kalku":8,"idle_s":212}]}
{"type":"cancel","id":3,"run":1}
{"type":"cancelled","id":3,"run":1}
```

### `error`

Same shape as in the kalku protocol, as a terminal event for any client request.

## Fixtures

`docs/protocol/fixtures/` holds every message as it travels on the wire, one variant per line:

```
shapes/            site.ndjson, delegated_outcome.ndjson, coverage.ndjson
kalku/requests/    one file per request type (kaikai side → kalku)
kalku/replies/     one file per reply type, plus progress (kalku → kaikai side)
client/requests/   one file per client request
client/events/     one file per server event
invalid/           <direction>.ndjson: {"expect": <protocol error kind>, "line": <raw line>}
                   framing.ndjson: {"expect": "line_too_long", "max": <bytes>, "input": <raw>}
```

Valid fixtures are in canonical form, so decoding and re-encoding a line reproduces it byte for byte. The kaikai side's tests and every kalku's tests validate against them. A message change without a fixture change does not land.
