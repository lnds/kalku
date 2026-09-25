# Elixir kalku

The native kalku for Elixir: it finds sites with Elixir's own parser and, in later stages, casts wekufe into a warm BEAM node with the project loaded. It speaks the kalku protocol (`docs/protocol.md`) on stdio.

## Status

| Message | Served |
|---|---|
| `hello` → `ready` | yes |
| `sites` → `sites_found` | yes, all six spells |
| `prepare` → `prepared` | yes |
| `baseline` → `baseline_done` | yes, with per-test coverage |
| `shutdown` → `bye` | yes |
| `cast`, `abort`, `reset`, `reload` | not yet: answered with a non-fatal `bad_request` |

`ready` announces only `cast` (the native kind); no capability is claimed before it works.

## Requirements

- Elixir **1.18** or newer (the built-in `JSON` module), OTP 27 or newer.
- No runtime dependencies: the kalku runs inside the user's project runtime, so it must not bring anything that could conflict with the project's own dependencies.

## Running

```sh
MIX_BUILD_PATH=<reni>/build bin/kalku-elixir
```

Run from the root of the project being measured. `bin/kalku-elixir` is the supported way to summon this kalku, and it is a shell script because every part of it is load-bearing:

- **`MIX_BUILD_PATH` inside the reni** is how a run leaves the project's own `_build` untouched. `prepare` checks it and refuses to compile anything if the build would land anywhere else — compiling a user's project into their tree, with mutated code, is the one thing a kalku must never do.
- **`MIX_ENV=test`**, because the suite is the oracle.
- **`mix deps.compile >&2` first**, because mix writes what it compiles to stdout, and stdout is the protocol. Starting the loop does not compile; one line of `==> kalku_elixir` on stdout is enough for the kaikai side to banish the worker for writing nonsense.
- **`exec`**, so the kalku is the process that receives a signal, with no shell in between.

Summoning `mix kalku.serve` directly still works once the dependencies are built, but on a cold start it puts the compiler's output on the protocol channel. The script exists so nobody has to remember that; it refuses to start at all without a `MIX_BUILD_PATH`.

## Preparing

`prepare` compiles the project into the reni with protocol consolidation **off** — a consolidated protocol is built from every implementation at once, so a wekufe cast into a `defimpl` would be silently ignored and counted as a survivor.

It answers `prepared {duration_ms, modules}`, or a fatal `error`:

| Code | When |
|---|---|
| `reni_not_isolated` | the build path is not inside the reni, or no reni was given |
| `wrong_env` | `MIX_ENV` is not `test` |
| `prepare_failed` | the project does not compile, naming the file and line |

`MIX_TEST_PARTITION` is read from `hello.env`, so each kalku can have its own test database.

## The baseline

`baseline` runs the suite once **inside the kalku's own runtime**, not in a `mix test` subprocess: a subprocess would take its results with it and leave the runtime cold for the casts that follow.

It answers `baseline_done` with `status` (`green` or `red`), every test named by where it is written (`test/green_test.exs:4`, relative to the project, so every kalku in a pool calls the same test the same thing), how long each took, and the failures quoted from ExUnit's own words.

Two things it does that are easy to miss:

- ExUnit's formatters write to stdout, which is the protocol, so the suite runs with a formatter that prints nothing and forwards each result to a collector that **outlives the suite** — ExUnit stops its formatters when the suite ends.
- The loaded test modules are remembered. ExUnit runs the suite it is given and does not keep it for a second run, and a file already required loads nothing the second time; a kalku runs the suite once per wekufe, not once per life.

### Per-test coverage

`baseline` reports which tests execute each line, which is what lets a wekufe be cast against the handful of tests that reach its line instead of the whole suite. Speed is the product, and most of it comes from here.

It costs a **second pass over the suite**, and that is not an oversight. `:cover` counts per line, not per test, and ExUnit delivers its formatter events asynchronously — a `test_started` can arrive after the test it announces has already run, so clearing counters there clears the *next* test's lines. The only honest attribution is to run each test on its own, with the counters cleared before it. That happens once, in the baseline.

Only the project's own modules are instrumented: nobody mutates a dependency, so counting its lines would cost time and say nothing.

Coverage travels inline when it fits under `hello.inline_limit_bytes`, and otherwise is written to `coverage.json` in the reni and reported as `coverage_path` — a megabyte of JSON per worker is a cost the protocol lets us decline.

A line no test runs has no entry at all: the question is which tests cover a line, and for an uncovered line the honest answer is none, which the kaikai side reads as `no_coverage` rather than as a hole.

The kalku declares OTP's `:tools` application, which is where `:cover` lives.

## Sites

Sources are parsed with `Code.string_to_quoted/2` (`columns`, `token_metadata`, and a literal encoder that keeps positions on literals). The AST decides what and where; the source text only confirms that the expected token sits at the reported position, and a node whose text cannot be pinned down exactly yields no site.

| Spell | Proposes |
|---|---|
| `arm` | delete one clause of `case`, `cond`, `with … else`, `receive`, `fn`, or a multi-clause function; whole lines, only when every clause starts its own line; never the only clause |
| `compare` | `>`↔`>=`, `<`↔`<=`, `==`↔`!=`, `===`↔`!==`, in expressions and guards |
| `connect` | `and`↔`or`, `&&`↔`\|\|` |
| `negate` | `if`↔`unless`; drop `not` / `!` |
| `literal` | integer `n`→`n+1`, `true`↔`false`, `:ok`↔`:error`, a non-empty plain string → `""` |
| `call` | drop one pipe stage; `f(x, …)` → `x` when `x` is a variable or a literal |

Never proposed: test files and scripts (`test/`, anything but `.ex`); `@moduledoc`, `@doc`, typespecs, and other directive attributes; anything inside calls matched by `exclude_calls`; literals inside `raise`; `if`/`unless` whose branches are identical; calls in patterns, guards, or a pipe's right side.

Every site carries its span (line, column in codepoints, byte offset), `original`, `replacement`, `enclosing` (`Module.fun/arity`), and `ordinal`. A candidate whose wekufe would not parse is dropped and counted on stderr.

## Tests

```sh
mix test
```

- `test/protocol_test.exs` — every fixture in `docs/protocol/fixtures/` for this direction decodes and re-encodes byte for byte; invalid fixtures fail with the expected kind.
- `test/sites_test.exs` — one golden per fixture under `test/fixtures/<spell>/`; every site of every fixture and of this kalku's own `lib/` round-trips and parses. After a reviewed change, `KALKU_UPDATE_GOLDENS=1 mix test` rewrites the goldens.
- `test/loop_test.exs` — the protocol loop, request by request.

`test/fixtures/` is excluded from `mix format`: those files are written a particular way on purpose.
