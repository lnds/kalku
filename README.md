# kalku

<p align="center">
  <img src="docs/img/mascot.png" alt="The kalku mascot: a black bird in a red ninja headband, grinning with a mouthful of teeth" width="380">
</p>

**Mutation testing for humans, CI, and coding agents.** Native where a language has none, and one honest gate over the tools that already exist.

kalku checks how good a test suite really is by breaking the code on purpose, one small defect at a time, and seeing whether any test notices. A defect that no test catches is a hole in the suite, and kalku reports it with a file, a line, and a diff.

Coding agents write tests fast, but a test can run a line without checking anything about it. Coverage can't catch that; mutation testing can. **Agents write tests fast; kalku tells them whether those tests test anything.**

It targets Elixir first. It is written in [kaikai](https://github.com/lnds/kaikai) and uses kalku to test its own code.

> **Status: pre-alpha.** `kalku init` and `kalku run` work: a project is set up in one command and measured in another, with the Elixir kalku or the kaikai one. The rest of the command line — `serve`, `cast`, `show`, `merge`, `info` — is still the design, and so are the flags marked below. Track progress in the [issues](https://github.com/lnds/kalku/issues).

## What works today

| Piece | State |
|---|---|
| **`kalku init`** — detects the language, writes `.kalku.toml` and the summoning script | done |
| **`kalku run <files>`** — measures those files and reports survivors | done |
| **Protocol** — both directions, canonical NDJSON, fixtures for every message | done |
| **Core** — planner, test selection, outcomes, score, gate, equivalents, `.kalku.toml`, sharding, changed lines | done |
| **Reports** — human, JSON, GitHub annotations, and `agent` NDJSON with per-spell hints | done |
| **Orchestrator** — kalku pool, scheduler, timeout escalation (abort → reset → kill), restart budget, cancellation | done |
| **Elixir kalku** — sites, `prepare`, `baseline` with per-test coverage, `cast`, `abort`, `reset`, `reload` | done: every protocol message but `delegate` |
| **kaikai kalku** — the one kalku measures itself with | done |
| **Session and cache keys** — one warm session per project, incremental re-casting | done |
| Per-test coverage *used* by a run (a cast still runs the whole suite) | not yet |
| `--since`, `--watch`, `--ci`, and the gate on changed lines | not yet |
| **`kalku info`** — spells, outcomes, formats and the agent loop, in the binary | done |
| **Server** (`kalku serve`), **MCP** | not yet |

Both kalku are driven end to end by their own test suites, over real pipes, against real fixture projects — and kalku measures its own suite through the kaikai one (`make self-mutate`).

## The cast

In Mapuche belief, a *kalku* is a sorcerer who works in a hidden cave, the *reni*, and commands the *wekufe*, spirits that bring harm. kalku borrows that cast:

| Term | Meaning |
|---|---|
| **kalku** | a worker bound to one language, which knows how to break code in that language |
| **wekufe** | a mutant: your program with exactly one defect cast into it |
| **spell** | a kind of defect: drop a `case` clause, shift `>=` to `>`, swap `and` for `or`, … |
| **reni** | an isolated workspace where wekufe are cast. Your working tree is never touched |

Reports use plain words (`killed`, `survived`, `timeout`) so a CI log reads without the glossary.

## How it works

```
             kaikai side                          kalku
  ┌───────────────────────────────┐     ┌──────────────────────┐
  │ plan · schedule · cache       │────►│ kalku[elixir] × N    │  warm BEAM nodes,
  │ score · report · gate CI      │◄────│ kalku[kaikai]        │  project loaded once
  └───────────────────────────────┘     └──────────────────────┘
                  versioned NDJSON protocol
```

1. **Baseline.** Run the suite once and record which tests cover which lines. If the suite is already failing, stop, because mutants on top of failing tests measure nothing.
2. **Plan.** Find the places where each spell applies, using the language's own parser rather than regexes. Skip lines no test covers and mutants declared equivalent.
3. **Cast.** Load each wekufe into a warm kalku and run only the tests that cover it. Stop at the first failing test.
4. **Report.** List the survivors first. The score, `killed / (killed + survived)`, comes after as context. Timeouts and crashes are reported separately and never counted as kills.

Warm workers are the point. A timeout is aborted inside the running VM, and a worker is restarted only when it stops responding. After the first run, a warm session only re-casts what changed, which makes watch mode and fast CI possible.

## Usage

```sh
kalku init                     # detect the language and set this project up
kalku run lib/thing.ex         # measure those files
kalku run lib/a.ex --limit 6 --verbose
kalku info outcomes            # what kalku knows about itself
kalku info agents --snippet    # the lines to paste into CLAUDE.md
```

`init` reads the project's own markers — `mix.exs`, `kai.toml` — says what
it found, and writes `.kalku.toml` plus a `.kalku/summon` script holding
whatever the toolchain needs to start the kalku with a clean protocol
channel. It never writes over a file that is there, and it never edits the
build file that decides what a project depends on: it prints the line to
add and says why.

Still the design, not the program:

```sh
kalku run                      # the whole project
kalku run --since main         # only the lines you changed
kalku run --watch              # re-cast on save, against warm workers
kalku run --ci --since origin/main --format github
```

In CI, a pull request fails when **a wekufe survives on a line it changed**, not when a global score dips. Old debt does not block new work. Exit codes separate *"your tests have holes"* (`1`) from *"kalku could not measure"* (`2`).

## For coding agents

People read one run and CI gates one run per pull request. An agent iterates: it finds a survivor, writes a test, checks whether the wekufe is dead, and repeats. kalku is built for that loop.

```sh
kalku run --since HEAD --format agent   # survivors as compact JSON, each with a hint
kalku cast <wekufe>                      # re-cast just that one, in seconds
kalku info agents                        # a snippet to paste into CLAUDE.md / AGENTS.md
```

- **Everything needed to act in one object:** file, line, enclosing function, the change, the source around it, the tests that cover it, and a hint. Hints come from a fixed template per spell (*"no test tells `i == 0` apart from `i > 0`"*), not from a model.
- **MCP** (not built yet): `kalku serve` will expose `kalku_run`, `kalku_cast`, `kalku_show`, and `kalku_propose_equivalent` as tools for Claude Code, Cursor, and similar hosts.
- **Guardrails:** an agent can only *propose* an equivalent mutant; a person has to accept it. Suppressions added in a pull request appear under their own heading in every report. A test only counts as killing a wekufe if it passes on the original code. A timeout never counts as a kill.

## Languages

| Language | kalku | How |
|---|---|---|
| Elixir | native | kalku owns the loop: warm BEAM nodes, in-memory loading, coverage-based test selection |
| kaikai | native (thin) | built on `kai mutate`; this is how kalku tests itself |
| others | driver | wraps an existing framework (Stryker, PIT, …), normalizes its results, and recomputes the score |

## Documentation

- [`docs/design.md`](docs/design.md): architecture, run lifecycle, outcomes and score, spells, equivalent mutants, CI, distribution.
- [`docs/protocol.md`](docs/protocol.md): the kalku and client protocols.
- [`CLAUDE.md`](CLAUDE.md) and [`.claude/rules/`](.claude/rules/): project principles and conventions.

## Building

Requires [kaikai](https://github.com/kaikailang-org/kaikai) (version in `.kaikai-version`), plus [`km`](https://github.com/lnds/kimun) and `jq` for the quality gate.

```sh
make build     # _build/kalku
make test      # kaikai tests, and the Elixir kalku's once it exists
make ci        # kaikai side: format check, lint, build, tests, km quality gate
make check     # everything: `make ci` plus the Elixir kalku's tests
```

### Measuring an Elixir project

The Elixir kalku runs *inside* the project it measures, so that project
depends on it:

```elixir
# mix.exs
{:kalku_elixir, path: "/path/to/kalku/adapters/elixir", only: :test, runtime: false}
```

Then, from the root of that project:

```sh
kalku init
kalku run lib/thing.ex --limit 6 --verbose
```

```
lib/green.ex:4  compare  Green.classify/1
  - >=
  + >
  covered by 4 tests
  No test tells `>=` apart from `>` in `Green.classify/1`. Add a case at the boundary where the two sides are equal.

score 60% · 3 killed · 2 survived · 1 compile error
```

One worker for now: each kalku needs a build path of its own, and there
is one `MIX_BUILD_PATH` to give. The kaikai side also does not yet use
the per-test coverage this kalku measures, so every wekufe is cast
against the whole suite.
### kalku measured by kalku

`make self-mutate` casts wekufe into kalku's own sources through the
kaikai kalku and reports what its suite did not notice:

```
kalku/core/shard.kai:30  literal  shard.wrap32/1
  - 4294967296
  + 0
  covered by 189 tests
  No test depends on the exact value `4294967296` in `shard.wrap32/1`.

score 40% · 2 killed · 3 survived · 1 compile error
```

One module at a time, and slow on purpose: kaikai reports no per-test
coverage and has no warm runtime to reload into, so every wekufe rebuilds
the package and runs the whole suite. `SELF_MODULE`, `SELF_LIMIT` and
`SELF_WORKERS` choose how much to measure and how hard. Leave the working
tree alone while it runs — each worker copies the project when it starts,
so an edit mid-run gets measured.

The same run happens nightly (`.github/workflows/self-mutate.yml`, also
startable by hand with a module and a limit) and its report is attached to
the run. It is advisory: a gate that blocks a pull request has to measure
the lines that pull request changed, and that does not exist yet.

## Contributing

- Code, docs, and commits are in English.
- Commits follow [Conventional Commits](https://www.conventionalcommits.org/): `feat(elixir): …`, `fix(scheduler): …`. Versions and `CHANGELOG.md` are generated by [commitizen](https://commitizen-tools.github.io/commitizen/) (`cz bump`). Do not edit them by hand.
- Pending work lives in GitHub issues.
