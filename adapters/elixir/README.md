# Elixir kalku

The native kalku for Elixir: it finds sites with Elixir's own parser,
casts wekufe into a warm BEAM node with the project loaded, and runs only
the tests that cover each one. It speaks the kalku protocol on stdio, to
[kalku](https://github.com/lnds/kalku).

This package is half of the tool. It runs *inside* the project it
measures — that is how a wekufe is loaded into a warm BEAM instead of
rebuilt — and the `kalku` binary drives it from outside.

## Install

```elixir
# mix.exs
{:kalku_elixir, "~> 0.1", only: :test, runtime: false}
```

Then install the `kalku` binary ([how](https://github.com/lnds/kalku#install))
and, from the root of your project:

```sh
mix deps.get
kalku init                 # detects Elixir, writes .kalku.toml and .kalku/summon
kalku run lib/thing.ex     # measure one file
```

## Status

| Message | Served |
|---|---|
| `hello` → `ready` | yes |
| `sites` → `sites_found` | yes, all six spells |
| `prepare` → `prepared` | yes |
| `baseline` → `baseline_done` | yes, with per-test coverage |
| `shutdown` → `bye` | yes |
| `cast` → `cast_done` | yes |
| `abort` → `aborted` | yes |
| `reset` → `reset_done` | yes |
| `reload` → `reloaded` | yes |

`ready` announces only `cast` (the native kind); no capability is claimed before it works.

## Requirements

- Elixir **1.16** or newer, OTP 26 or newer.
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

### A project that does not depend on it

`bin/kalku-elixir` also measures a project whose `mix.exs` does not name `kalku_elixir`, and leaves that project as it found it. It compiles the kalku with the project's own Elixir into `$MIX_BUILD_PATH/kalku_elixir/ebin` — inside the reni, built once and again only when the Elixir, the OTP or the kalku changes — and starts `mix kalku.serve` with that directory on the code path.

Mix takes off the code path whatever a project does not depend on, each time it compiles. So the kalku loads all of its own modules as it starts, and asks for `:tools` and `:crypto` after `prepare` has compiled rather than before.

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

Three things it does that are easy to miss:

- ExUnit's formatters write to stdout, which is the protocol, so the suite runs with a formatter that prints nothing and forwards each result to a collector that **outlives the suite** — ExUnit stops its formatters when the suite ends.
- The project writes to stdout too, from anywhere: a test that prints, a process of an application, the application as it starts. The loop keeps the device behind stdin and stdout for the protocol and makes standard error the group leader of every process that had it, so what the project prints is on stderr and what it starts afterwards inherits that.
- The loaded test modules are remembered. ExUnit runs the suite it is given and does not keep it for a second run, and a file already required loads nothing the second time; a kalku runs the suite once per wekufe, not once per life.

### Per-test coverage

`baseline` reports which tests execute each line, which is what lets a wekufe be cast against the handful of tests that reach its line instead of the whole suite. Speed is the product, and most of it comes from here.

It costs a **second pass over the suite**, and that is not an oversight. `:cover` counts per line, not per test, and ExUnit delivers its formatter events asynchronously — a `test_started` can arrive after the test it announces has already run, so clearing counters there clears the *next* test's lines. The only honest attribution is to run each test on its own, with the counters cleared before it. That happens once, in the baseline.

The pass is kept close to what the tests themselves cost. A test is run in the module it is written in, not in the whole suite with the rest excluded, and after it only the modules it entered are read and cleared: reading `:cover` costs time per module, and one test enters few of a large project's. While it runs, a line on stderr every ten seconds says how many tests are done and where the time went.

Only the project's own modules are instrumented: nobody mutates a dependency, so counting its lines would cost time and say nothing.

Coverage travels inline when it fits under `hello.inline_limit_bytes`, and otherwise is written to `coverage.json` in the reni and reported as `coverage_path` — a megabyte of JSON per worker is a cost the protocol lets us decline.

A line no test runs has no entry at all: the question is which tests cover a line, and for an uncovered line the honest answer is none, which the kaikai side reads as `no_coverage` rather than as a hole.

The kalku declares OTP's `:tools` application, which is where `:cover` lives.

## Casting

`cast` splices the site's span into the module's source **in memory**, compiles from a string, and loads the result into the warm runtime. Nothing is written to the project: the file on disk is the one the developer left there, before the cast and after it.

The original modules are kept before anything is compiled and reloaded on every path out, including a compile error. A wekufe that outlived its cast would be attributed to the next one, and the next one's result would be a lie.

While its tests run, the wekufe's compiled modules are also written to a directory of their own in the reni, at the front of the code path. Code that asks for a module's object code — a mocking library does, to build the copy it keeps of a module it replaces — is then given the wekufe and not the original from the build. The originals are never written over.

| Outcome | When |
|---|---|
| `equivalent` | the compiled code is the original's, so no test could notice; no test is run |
| `killed` | a selected test failed, and `killed_by` names it |
| `survived` | every selected test ran and none noticed |
| `compile_error` | the wekufe does not compile, with the first line of why |

Equivalence is **proved rather than guessed**: the wekufe's modules and the original's are compared by their BEAM MD5s, which is the runtime's own answer to "is this the same code". A kalku never reports an equivalence it cannot demonstrate.

Only the tests in `cast.tests` run, selected by file and line the way `mix test path:line` does, stopping at the first failure. Those are the tests the baseline's coverage says reach the changed line; running the rest would cost time and could not change the answer.

## Aborting

`abort` stops a cast where it stands and keeps the kalku warm, which is the whole point: a warm kalku is the most expensive thing kalku owns.

For it to be possible at all, the loop **reads while it works**. Reading happens in one process and the cast in another, so a loop that read one line, answered it, and only then read again could never receive an `abort` — the message only matters in the middle of the cast it stops.

Killing the casting process is not enough. ExUnit runs each test in a process it *monitors* rather than links, so a test looping forever outlives the cast that started it and would burn a core for the rest of the run — measured, not assumed. So an abort also stops everything unnamed that appeared while the cast ran. That is coarse on purpose: a wekufe is the reason any of it is there.

Casts are served one at a time, in the order they arrive. A kalku has exactly one runtime, so two casts at once would measure each other; one that arrives early waits rather than being refused. `shutdown` finishes what is under way before saying `bye`, since leaving without it would have the kaikai side report a measured wekufe as crashed. Input that ends with no `shutdown` is the opposite case, a run that was killed: the kalku ends within half a second, in the middle of a suite if it has to, and kills every program it and the tests started.

## Reloading, and what depends on what

Between runs a developer edits, and a kalku that stayed warm is holding the code from before. `reload {files}` recompiles them — and whatever is stitched into them at compile time.

A module that uses another's macro has the expansion baked in. Recompiling only the macro's own module leaves the caller running the old one, so a wekufe cast there is **loaded but not running anywhere a test can reach it**. It comes back `survived`, and that is a false survivor: a hole reported where the measurement never happened. The fixture shows it as a test — the same wekufe, the same test, `survived` with `reload: "module"` and `killed` with `reload: "dependents"`.

So `sites` marks each site with the reload its file needs, and a `cast` on a `dependents` site recompiles the dependents too and restores them afterwards. The graph comes from `mix xref`, the compiler's own answer rather than a guess of ours; its output is captured rather than printed, and the quiet shell the kalku runs under is lifted for the length of the question, since a quiet shell answers nothing.

`reload` also makes the kalku forget the suite it loaded, since a test file may be among the changed ones.

## Dirty state and resetting

State one cast leaves behind is read as the next cast's doing, and the next result is a lie. So each cast is weighed against a mark of what a clean runtime looks like, and one that moved anything answers `dirty: true`.

What is watched is **named ETS tables and the project's application env**. Anonymous tables belong to whoever holds them and vanish with it; processes come and go under a supervisor without anything being wrong. Watching those would call a healthy runtime dirty on every cast — measured, not assumed: a snapshot of the two that are watched is identical across runs.

The mark is taken again **after the baseline**, and that one is what counts. The suite creates named tables the first time it runs, so a mark from before them would have `reset` delete the test framework's own state and leave the kalku unable to run a test at all. That is exactly what happened when the mark was taken in `prepare`: the reset succeeded, reported `clean: true`, and every cast after it survived because nothing could run.

`reset` deletes the tables the project added, restores the application env entry by entry — both directions, since restoring only known keys would leave the additions — and restarts the application. It answers `clean` with whether the runtime matches the mark afterwards; a reset that did not work says so, and the kaikai side recycles the kalku rather than trusting it.

Nothing may touch the runtime while a cast is using it, so a `reset` that arrives mid-cast waits for it. A reset in the middle of a cast cleans up the very state that cast was about to be judged on, and both answers come out wrong.

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
