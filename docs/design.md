# kalku — design

kalku measures how good a test suite is by breaking the code on purpose and checking whether the tests notice. It is **native mutation testing where none exists** — Elixir first — **and one honest gate over the tools that already exist** elsewhere. It is built as a kaikai orchestrator driving small per-language kalku, and it runs on its own sources.

## Glossary

In Mapuche belief, a *kalku* is a sorcerer who works in a hidden cave, the *reni*, and commands the *wekufe*, malign spirits that bring harm. kalku borrows that cast. The vocabulary is fixed; use these words and no others.

| Term | Meaning |
|---|---|
| **kaikai side** / **orchestrator** | The core, written in kaikai. Plans, schedules, caches, scores, reports. Runs as the CLI or the server. |
| **kalku** | A worker process bound to one language: the adapter running inside a warm runtime (for Elixir, a BEAM node with the project loaded). A kalku knows its language's dark arts and nothing else. |
| **reni** | A project's isolated workspace: build artefacts, caches, per-worker databases. Mutants live and die here, never in the user's tree. |
| **wekufe** | A mutant: the original program with exactly one defect cast into it. |
| **spell** | A mutation operator: a class of defect a kalku can cast (`arm`, `compare`, …). |
| **site** | A place in the source where a spell applies: file, token span, spell, replacement text. |
| **summon** / **banish** | Start / stop a kalku. |

Outcomes (`killed`, `survived`, `timeout`, …) keep plain technical names: they appear in CI logs and must read without the glossary. A surviving wekufe is a hole in the suite, with a file, a line, and a diff.

## Goals

1. **Trustworthy results.** A wekufe differs from its parent by one defect and nothing else. Only a failing test counts as a kill. Survivors come first; the score is secondary.
2. **Never harm the user's project.** No wekufe is ever written to the working tree.
3. **Fast enough to run on every change.** Minutes on a first run, seconds on incremental ones.
4. **Language-agnostic core.** A new language costs one kalku, not a fork of the orchestrator.
5. **Self-hosted.** kalku measures its own suite with its own kaikai kalku.

## Non-goals

- Replacing a language's existing property-testing libraries (e.g. StreamData). Property testing arrives later as *synthesis* of properties from declared laws, not as a new generator framework.
- Parsing target languages in kaikai.
- Remote execution of untrusted repositories (see *Later: farm mode*).

## Architecture

```
            clients                        kaikai side                          kalku
  ┌──────────────────────┐      ┌──────────────────────────────┐       ┌──────────────────┐
  │ kalku CLI            │      │ session (one per project)    │       │ kalku[elixir] #1 │
  │ kalku run --watch    │─────►│  ├─ planner                  │──────►│ kalku[elixir] #2 │
  │ editor / LSP         │ NDJSON│  ├─ scheduler + supervisor  │ NDJSON│ ...              │
  │ CI                   │◄─────│  ├─ cache                    │◄──────│ kalku[elixir] #N │
  └──────────────────────┘      │  └─ scorer + reporter        │       └──────────────────┘
                                └──────────────────────────────┘               │
                                               │                               ▼
                                               └────────────── reni (per project, isolated)
```

- **Clients** speak the client protocol to the server over a Unix socket.
- **The kaikai side** holds one **session** per project root. Planner, scheduler, cache, and scorer are pure logic behind effect rows; only the edges spawn processes or touch the network.
- **Each kalku** speaks the kalku protocol (`docs/protocol.md`) over stdio. Kalku are supervised actors on the kaikai side and are meant to stay warm: a hung cast is aborted inside the kalku, and a kalku is banished only when it stops answering (see *Timeouts and dirty state*).
- **The reni** lives under `$XDG_CACHE_HOME/kalku/<project-hash>/`. It holds build artefacts and caches, never source copies: source is read from the working tree, read-only.

### One binary, two lifetimes

The CLI and the server are the same code. `kalku run` connects to a running server, or starts one. `kalku run --ci` runs the orchestrator in-process for exactly one run and exits. A CLI run is a server whose lifetime is one run, so nothing is written twice.

## Kinds of kalku

Not every language needs kalku to do the mutating. Where a mature mutation framework exists, a kalku can drive it instead. A kalku announces which kind it is through `ready.capabilities`, and the kaikai side offers what that kind allows.

| Kind | For | Who runs the loop | Warm between runs |
|---|---|---|---|
| **native** | languages with no framework (Elixir) | the kaikai side: `sites`, `cast`, `reload` | yes: the kalku's runtime stays loaded |
| **driver, API** | frameworks with a programmatic API (e.g. Stryker's JS API) | the framework, kept alive by the kalku | yes: the kalku keeps the framework alive |
| **driver, batch** | frameworks that are only a CLI | the framework, one process per run | no: the framework starts and exits each run |

A framework that would help a native kalku — for instance, to reuse its spells — is used as a **library inside** the kalku; the kalku still owns its runtime.

### What a driver keeps and loses

A driver kalku still gets everything that needs only a file and a line: survivors-on-changed-lines gating, the ratchet, `--format github`, `kalku merge`, and one gate and one report across every language in a monorepo — something no single framework offers.

It loses what depends on owning the loop: kalku's coverage-based selection, trivial compiler equivalence, and — when the framework does not expose the enclosing function — the semantic key for declared equivalents, which falls back to a line-based key. The report says so.

### Normalisation

Frameworks count differently: some treat timeouts, or memory errors, as kills. A driver never forwards a framework's score. It maps each individual status to a kalku outcome, keeps the original in `tool_status`, and the kaikai side recomputes the score with its own formula:

| Framework status | kalku outcome |
|---|---|
| Killed | `killed` |
| Survived | `survived` |
| Timeout | `timeout` (outside the score) |
| NoCoverage | `no_coverage` |
| CompileError | `compile_error` |
| RuntimeError, MemoryError | `crashed` (outside the score) |
| Ignored | excluded |

kalku's score for a project will therefore often be lower than the framework's own. That is the point, and the docs say it up front.

Where frameworks emit the shared *mutation-testing-report-schema* (from the mutation-testing-elements project), one driver can read all of them. kalku also emits that schema as an output format, so its reports work with existing viewers.

### The kaikai kalku

kaikai already has `kai mutate`, split the same way the protocol is: `kaic2 --mutate-list-json` catalogues sites from the AST, `kaic2 --mutate-apply <i>` writes the spliced source, and a shell driver owns the run loop. The kaikai kalku is a thin native kalku over those flags:

- `sites` → `--mutate-list-json`;
- `cast` → `--mutate-apply`, write the wekufe into the reni, build, run the covering tests with `kai test`;
- `code_hash` → a hash of the emitted code, which is deterministic in kaikai.

kaikai compiles to native code and cannot hot-load, so "warm" here means a warm compiler and build cache, not a warm runtime; the kalku does not announce `hot_load`.

This kalku needs from kaikai: the full site data in `--mutate-list-json` (span, original and replacement text, enclosing declaration, ordinal), and machine-readable results plus test selection in `kai test`. Without the latter it degrades to whole-suite runs with no `killed_by`.

`kai mutate` keeps its own driver for kaikai's CI. kalku is written in kaikai, so kaikai's CI must never depend on kalku: that would make the bootstrap circular. The two share the site format, not code.

## Lifecycle of a run

```
$ cd my_app && kalku run --since main
```

### 1. Connect

The CLI looks for `$XDG_RUNTIME_DIR/kalku.sock`. If no server answers, it starts one in the background and waits for the socket. It sends `run {root, scope}` and streams events back.

### 2. Open the session

The server keys sessions by canonical project root. A new root opens a session in state `cold`; a known root reuses its warm session. The language is detected from marker files (`mix.exs` → Elixir). An unknown language is an error, not a guess.

### 3. Prepare the reni

The project is compiled into the reni with the language's own tooling, redirected away from the user's artefacts. For Elixir: `MIX_BUILD_PATH` points into the reni, `MIX_ENV=test`, protocol consolidation disabled so a mutated `defimpl` can take effect. The user's `_build` and working tree are never written.

### 4. Summon the kalku

N kalku are summoned (default: CPU cores). Each loads the compiled project and starts the test environment once. Kalku that need exclusive external resources get their own: for Elixir with a database, each kalku runs with a distinct `MIX_TEST_PARTITION` and therefore its own test database.

### 5. Baseline

One kalku runs the full suite once with per-test coverage. The baseline yields:

- **Green or red.** A red suite aborts the run: mutating on top of failing tests measures nothing.
- **Coverage map:** `(file, line) → [test ids]`.
- **Durations** per test, from which each wekufe's timeout is derived (`max(3 × covering tests' baseline, floor)`).

The baseline is cached by the hashes of all source and test files; an unchanged project reuses it.

### 6. Plan

A kalku reports the sites in the files within scope (`--since <ref>` narrows to changed lines). The planner then:

- drops sites listed in `.kalku/equivalent` (see *Equivalent wekufe*), and fails the run if an entry matches no site;
- marks sites on lines no test covers as `no_coverage` and does not run them;
- reuses cached outcomes whose key is unchanged;
- orders the rest so that cheap, high-signal wekufe run first (fewest and fastest covering tests).

### 7. Cast

For each planned wekufe, the scheduler picks an idle kalku and sends it the site plus the covering tests. The kalku:

1. splices the replacement into the module's source in memory;
2. compiles it; if the compiled code is identical to the original's, stops here and reports `equivalent` with evidence (see *Trivial compiler equivalence*);
3. loads it into its runtime — or, if the module is a compile-time dependency of others (macros, computed module attributes, `require`/`import`), recompiles the dependents too;
4. runs only the covering tests, stopping at the first failure;
5. restores the original modules;
6. reports the outcome and the hash of the compiled wekufe.

#### Timeouts and dirty state

A warm kalku is the most expensive thing kalku owns, so killing one is the last resort, never the normal path.

A timeout escalates in three steps:

1. The kaikai side decides the timeout from outside, as always, and sends `abort`.
2. The kalku stops the cast inside its runtime — for Elixir, `Process.exit(pid, :kill)` on the test processes, since a looping wekufe hangs a process, not the VM — restores the original modules, and answers `aborted`. The outcome is `timeout`; the kalku stays warm.
3. Only if `aborted` does not arrive within a grace period (a NIF that never returns, a blocked scheduler) is the OS process killed and a fresh kalku summoned.

A cast may leave global state behind (ETS tables, registered processes, application env). The kalku reports `dirty: true`, and the kaikai side escalates the same way: first `reset` — for Elixir, restart the project's applications and clear its ETS tables — and only if the reset fails, recycle the kalku.

### 8. Report

Outcomes stream to the client as they arrive and are written to the cache. At the end the reporter emits the summary: human text, JSON (`--format json`), and optional GitHub annotations for survivors.

The human report leads with **survivors** — file, line, spell, diff, covering tests — because a survivor is something a reader can act on. The score follows as context and trend, never as the headline.

### 9. Stay warm

After the run the session stays in `ready`. On the next run, the server hashes the source files, tells each kalku to reload only what changed, reuses coverage for untouched tests, and re-casts only the wekufe whose cache key changed. `kalku run --watch` does this on every save.

If a file changes during a run, outcomes for wekufe in that file or covered by tests that read it are discarded, not reported.

### 10. Idle shutdown

A session with no activity for a configurable period (default 15 minutes) banishes its kalku and closes. The reni stays on disk for the next cold start.

## Session states

```
cold ──prepare──► preparing ──ok──► baseline ──green──► ready ◄──────┐
                     │                  │                 │          │
                   error               red              run ──► running
                     ▼                  ▼                 │
                  failed             failed             idle
                                                          ▼
                                                        closed
```

`failed` sessions keep their diagnostics until the next `run`, which restarts from `cold`.

## Outcomes and score

| Outcome | Meaning | In score |
|---|---|---|
| `killed` | a covering test failed | detected |
| `survived` | every covering test passed | undetected |
| `timeout` | exceeded its timeout | reported apart |
| `no_coverage` | no test executes the site | reported apart |
| `compile_error` | the wekufe does not compile | excluded |
| `crashed` | the kalku died for a reason other than a timeout | reported apart |
| `equivalent` | proven by identical bytecode, or declared with a written reason | excluded |

**Score = killed / (killed + survived).** Timeouts, crashes, and missing coverage are shown next to the score, never folded into it: an infinite loop is not a failing assertion, and pretending it is inflates the number. A `compile_error` rate above a threshold is itself a warning: it means a spell is proposing nonsense for that language.

Equivalents are counted next to the score by origin — `score 0.86 · 14 equivalent (9 bytecode, 5 declared)` — so a growing pile of declared suppressions is visible.

## Spells

The shared catalog, in order of diagnostic value:

| Spell | Defect it simulates | Elixir forms |
|---|---|---|
| `arm` | a case stops being handled | delete a clause of `case`, `cond`, `with … else`, `receive`, or a multi-clause function |
| `compare` | off-by-one in a gate | `>=`→`>`, `<`→`<=`, `==`→`!=`, in expressions and `when` guards |
| `connect` | a gate that never or always fires | `and`↔`or`, `&&`↔`\|\|` |
| `negate` | an inverted guard | `if`↔`unless`, `c` → `not c` in conditions |
| `literal` | wrong seed or base case | `0`→`1`, `n`→`n+1`, `true`↔`false`, `:ok`↔`:error`, `""` for a non-empty string |
| `call` | a step that silently does nothing | drop a pipe stage (`\|> f()`), `f(x)` → `x` |

A language may add spells; each new spell gets a shared name in the protocol and fixtures in its adapter. Sites are never proposed inside tests, comments, docs, or string contents.

## Equivalent wekufe

Deciding whether two programs are equivalent is undecidable in general, so no tool finds every equivalent wekufe. kalku avoids generating the predictable ones, proves the cheap ones mechanically, and makes declaring the rest honest and durable.

### Three kinds of survivor

| Kind | Example | Treatment |
|---|---|---|
| **Equivalent** | `i >= 0` → `i > 0` where `i` is never 0 at that point | proven by bytecode, or declared with a reason |
| **Unobservable by design** | dropping `Logger.debug/1`, `:telemetry.execute/3`, or a cache that exists only for speed | excluded by configuration (`exclude_calls`), never entry by entry |
| **Hard to kill, but real** | an edge case no test exercises | a hole: write the test, do not suppress |

Keeping unobservable calls out of the equivalent file keeps that file small enough to audit.

### Not generating them

A kalku does not propose sites whose wekufe are equivalent by construction, for example:

- `literal` on text that only reaches a log message, `@moduledoc`/`@doc`, or a `raise` message;
- `negate` on an `if` whose branches are identical;
- sites inside calls matched by `exclude_calls`.

Each such rule has a fixture in the kalku showing the case it avoids. An `arm` on a clause no test executes is not a generation concern: it falls into `no_coverage`.

### Trivial compiler equivalence

If a wekufe compiles to the same code as the original, it is equivalent, and that is a proof, not a guess. The kalku already compiles every wekufe during a cast, so the check costs one comparison: for Elixir, the `.beam` code chunks with debug info and line numbers stripped. An identical result ends the cast with outcome `equivalent` and evidence `identical_bytecode`, without running a test.

The kalku also reports a hash of every compiled wekufe. Two wekufe with the same hash are the same program; the cache keys outcomes by that hash too, so a duplicate is cast once.

This is the only way a kalku may report `equivalent`: with mechanical evidence.

### Declared equivalents

The rest are declared in `.kalku/equivalent`, committed with the project. Entries are keyed by meaning, not by line number, so they survive edits elsewhere in the file:

```
lib/my_app/parser.ex  MyApp.Parser.next_token/2  compare  ">="  #1   # index is always even here; > and >= agree
```

Fields: file, enclosing function (`Module.fun/arity`), spell, original text (JSON string), ordinal of that text within the function, and the reason after `#`.

- An entry without a reason is a hard error.
- An entry that matches no site is a hard error: an orphan either suppresses nothing or, worse, would end up suppressing a different wekufe than the one justified.
- The file suppresses; it does not excuse. A wekufe that survives because a test is missing belongs in a test, not here.

## Configuration

`.kalku.toml` at the project root, all keys optional:

```toml
[run]
workers = 8
spells = ["arm", "compare", "connect", "negate", "literal", "call"]
exclude = ["lib/my_app_web/telemetry.ex"]
exclude_calls = ["Logger.*", ":telemetry.execute", "IO.inspect"]
timeout_floor_ms = 500

[score]
threshold = 0.80        # global floor; --ci exits 1 below it
ratchet = true          # the global score may not drop below the stored baseline

[elixir]
partition_env = "MIX_TEST_PARTITION"
```

## Caching

Cache entries live in the reni. A wekufe outcome's key is:

`hash(adapter version, spell, site span, source file hash, hashes of the covering tests' files, hashes of the files those tests load)`

Outcomes are also indexed by the compiled wekufe's hash, so identical programs reached from different sites share one cast.

Any change to a key component invalidates it. Keys never use timestamps.

## Running in CI

`kalku run --ci` runs the orchestrator in-process for one run: no server, no socket.

### Two cadences

| When | Command | Purpose |
|---|---|---|
| every PR | `kalku run --ci --since origin/<base>` | block the merge if the change introduces holes |
| nightly | `kalku run --ci --all --shard i/n`, then `kalku merge` | track the global score |

A full run on every PR does not scale: a medium Elixir project yields thousands of wekufe.

### What blocks a PR

A PR fails when **a wekufe survives on a line the PR changed**. Gating PRs on the global score punishes whoever touches a file with old debt, and teams switch such tools off. The global score is guarded separately by `[score] threshold` and, with `ratchet = true`, by a stored baseline it may not drop below.

### Exit codes

| Code | Meaning |
|---|---|
| `0` | measured; nothing to report |
| `1` | measured; survivors on changed lines, or the score is below threshold or baseline |
| `2` | could not measure: red baseline, failed prepare, orphan equivalent entry, config error |

"Your tests have holes" and "kalku could not run" are different failures and CI must be able to tell them apart.

### Sharding

`--shard i/n` takes a deterministic partition of the planned wekufe (by `site_id`), so shards never overlap and reruns pick the same work. Each shard writes a JSON report; `kalku merge` combines them into one score and one report.

### Cache

The reni is saved between builds (e.g. `actions/cache` on `~/.cache/kalku`, keyed by the hash of `mix.lock` and restored by prefix). Baseline, coverage, and unchanged outcomes are reused, so incremental runs work in CI too.

### Output

`--format github` emits an annotation on each survivor's line with the wekufe's diff, and a summary to `$GITHUB_STEP_SUMMARY`. `--format json` is the stable machine-readable report used by `kalku merge`.

### External resources

Kalku run in parallel, so each needs its own external state. For Elixir with Postgres, `prepare` creates one test database per kalku through `MIX_TEST_PARTITION`; the CI job provides the server as a service container.

```yaml
- uses: actions/cache@v4
  with:
    path: ~/.cache/kalku
    key: kalku-${{ hashFiles('mix.lock') }}-${{ github.sha }}
    restore-keys: kalku-${{ hashFiles('mix.lock') }}-
- run: kalku run --ci --since origin/${{ github.base_ref }} --format github
```

## Distribution

kalku ships as a **standalone native binary** per platform (linux-x64, linux-arm64, macOS arm64/x64), plus a GitHub Action that downloads it. Users, and CI images in particular, never need the kaikai toolchain.

Each language's kalku travels inside the binary as source and is compiled into the reni on first use with the project's own toolchain (for Elixir, the project's Elixir/OTP versions), so it always matches the runtime it will be loaded into.

## Self-hosting

kalku runs on its own sources through the kaikai kalku, in CI like any other project: survivors on changed lines block a PR.

The kalku that measures kalku is the **last release**, pinned and bumped deliberately, never the binary built from the same commit. A bug in the kalku under test could otherwise hide its own survivors — the same reason a compiler bootstraps from a previous stage. Until the first release exists, self-hosted runs use the freshly built binary and are advisory: they report but do not block.

## Security

Running wekufe is running modified code; serving run requests is remote code execution by definition.

- The server listens on a Unix socket with `0600` permissions, or `127.0.0.1` plus a per-session token. Never on a public interface by default.
- Every cast has a timeout; every kalku is supervised. Killing a kalku's process is the last step of an escalation, not the first response.
- Kalku receive an explicit environment, not the server's.

## Later: property synthesis

kaikai synthesises property checks from protocol laws declared in the source. The equivalent for a target language is to derive properties from what the code already states — Elixir `@spec`s, protocol implementations, encode/decode pairs marked as inverses — and emit them as tests in the language's own property framework (StreamData), so they run like any other test and are measured by the same wekufe.

## Later: survivor triage

For hard survivors, a model can be asked for one of two things, and kalku checks the answer:

- **A test that kills it.** Verified mechanically: the test must pass on the original and fail on the wekufe. If it does, the hole is closed with evidence.
- **An argument that it is equivalent.** Not verifiable, so it stays a proposal: a candidate `.kalku/equivalent` entry for a person to review, never applied automatically.

What can be verified is verified; what cannot stays a human decision.

## Later: farm mode

`run {git: url, ref: sha}` against a remote server that clones and runs on bigger hardware or across machines. It executes third-party code remotely, so it requires container sandboxing and real authentication before it ships. Not part of the initial design.

## Open questions

- Editor integration: LSP diagnostics for survivors, or a lighter file-based report the editor watches.
