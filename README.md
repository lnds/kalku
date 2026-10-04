# kalku

<p align="center">
  <img src="docs/img/mascot.png" alt="The kalku mascot: a black bird in a red ninja headband, grinning with a mouthful of teeth" width="380">
</p>

**Mutation testing for humans, CI, and coding agents.** Native where a language has none, and one honest gate over the tools that already exist.

kalku checks how good a test suite really is by breaking the code on purpose, one small defect at a time, and seeing whether any test notices. A defect that no test catches is a hole in the suite, and kalku reports it with a file, a line, and a diff.

Coding agents write tests fast, but a test can run a line without checking anything about it. Coverage can't catch that; mutation testing can. **Agents write tests fast; kalku tells them whether those tests test anything.**

It targets Elixir first. It is written in [kaikai](https://github.com/lnds/kaikai) and uses kalku to test its own code.

> **Status: pre-alpha.** Kalku for Elixir, Rust, Python and kaikai ship in the binary on Homebrew, and the Elixir one is also on [Hex](https://hex.pm/packages/kalku_elixir). Everything below runs today; [what is done and what is not](#what-works-today) is at the end.

## Install

```sh
brew install lnds/kalku/kalku
```

macOS on Apple Silicon and Linux on x86_64 with GLIBC 2.38 or newer —
the platforms [kaikai](https://github.com/lnds/kaikai) publishes a
toolchain for, and its floor is kalku's floor. The
same tarballs are on every [release](https://github.com/lnds/kalku/releases):
unpack it and put `kalku`, `kalku-kaikai`, `kalku-rust` and `kalku-python` on your `PATH`.

From source, with kaikai installed (the version in `.kaikai-version`):

```sh
git clone https://github.com/lnds/kalku
cd kalku
make install                  # PREFIX=/usr/local by default
kalku info                    # check it answers
```

### As an MCP server

`kalku mcp` serves the four agent tools over stdio, so a coding agent can
measure, re-cast and read a wekufe without shelling out. It takes no
arguments and reads no configuration of its own: the project is whichever
one the host is working in, or the `root` a tool call names.

Claude Code:

```sh
claude mcp add kalku -- kalku mcp
```

Cursor, Windsurf, Zed, Claude Desktop and anything else that reads an
`mcpServers` block:

```json
{
  "mcpServers": {
    "kalku": {
      "command": "kalku",
      "args": ["mcp"]
    }
  }
}
```

`kalku` has to be on the `PATH` the host starts with, which is not always
the one your shell has — a GUI app on macOS does not read your profile. If
the host cannot find it, give the absolute path from `which kalku`.

The tools are `kalku_run`, `kalku_cast`, `kalku_show` and
`kalku_propose_equivalent`. There is deliberately no tool that suppresses,
excludes or ignores anything; see [For coding agents](#for-coding-agents).

`kalku_run` runs the project's suite many times over and takes minutes, so
point it at a file or two rather than at everything.

## Measure an Elixir project

The Elixir kalku runs *inside* the project it measures — that is how it
loads a wekufe into a warm BEAM instead of rebuilding — so the project
depends on it, for tests only:

```elixir
# mix.exs, in deps/0
{:kalku_elixir, "~> 0.1", only: :test, runtime: false}
```

Then, from the root of that project:

```sh
mix deps.get
kalku init                    # detects Elixir, writes .kalku.toml and .kalku/summon
kalku run lib/thing.ex        # measure one file
```

`init` reads the project's own markers — `mix.exs`, `kai.toml` — says what
it found, and writes `.kalku.toml` plus a `.kalku/summon` script holding
whatever the toolchain needs to start the kalku with a clean protocol
channel. It never writes over a file that is there, and it never edits the
build file that decides what a project depends on: it prints the line to
add and says why.

Start with one file and a limit. A first whole-project run on a real
codebase is long, and there is nothing to learn from it that a single
file does not already show.

## What a run tells you

```sh
kalku run lib/green.ex --limit 6 --verbose
```

```
lib/green.ex:4  compare  Green.classify/1
  - >=
  + >
  covered by 4 tests
  No test tells `>=` apart from `>` in `Green.classify/1`. Add a case at the boundary where the two sides are equal.

score 60% · 3 killed · 2 survived · 1 compile error
```

Survivors come first and the score comes after, because the survivor is
the thing you can act on. `covered by 4 tests` is not decoration: the
baseline records which tests reach which lines, and a wekufe is cast
against those tests alone. A site no test reaches is `no_coverage`, never
a kill — and neither is a timeout, a crash, or a wekufe that did not
compile. The score is `killed / (killed + survived)` and nothing else
moves it.

Read one back, or re-cast it after writing a test:

```sh
kalku show <wekufe>            # site, diff, covering tests, hint, history
kalku cast <wekufe>            # re-cast just that one
kalku info outcomes            # what each outcome means
```

## Keep it warm

A run spends most of its time setting the project up. `kalku serve` keeps
the kalku a project was measured with, so the next run casts over a warm
one:

```sh
kalku serve                    # a 0600 Unix socket, streaming a run as it happens
```

The pool is thrown away the moment any source or test file changes:
measuring with a kalku that holds yesterday's code is measuring
yesterday's code. It serves one client at a time.

## For coding agents

People read one run and CI gates one run per pull request. An agent
iterates: it finds a survivor, writes a test, checks whether the wekufe is
dead, and repeats. kalku is built for that loop.

```sh
kalku run lib/thing.ex --format agent   # survivors as compact JSON, each with a hint
kalku cast <wekufe>                     # re-cast just that one, against a warm kalku
kalku info agents --snippet             # lines to paste into CLAUDE.md / AGENTS.md
```

- **Everything needed to act in one object:** file, line, enclosing function, the change, the source around it, the tests that cover it, and a hint. Hints come from a fixed template per spell (*"no test tells `i == 0` apart from `i > 0`"*), not from a model.
- **MCP:** `kalku mcp` serves `kalku_run`, `kalku_cast`, `kalku_show` and `kalku_propose_equivalent` over stdio to Claude Code, Cursor and similar hosts. There is deliberately no tool that suppresses, excludes or ignores anything. [How to install it](#as-an-mcp-server).
- **Guardrails:** an agent can only *propose* an equivalent mutant; a person has to accept it. Suppressions added in a pull request appear under their own heading in every report. A test only counts as killing a wekufe if it passes on the original code. A timeout never counts as a kill.

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
4. **Report.** List the survivors first. The score comes after as context. Timeouts and crashes are reported separately and never counted as kills.

Warm workers are the point. A timeout is aborted inside the running VM, and a worker is restarted only when it stops responding. After the first run, a warm session only re-casts what changed, which makes watch mode and fast CI possible.

## The cast

In Mapuche belief, a *kalku* is a sorcerer who works in a hidden cave, the *reni*, and commands the *wekufe*, spirits that bring harm. kalku borrows that cast:

| Term | Meaning |
|---|---|
| **kalku** | a worker bound to one language, which knows how to break code in that language |
| **wekufe** | a mutant: your program with exactly one defect cast into it |
| **spell** | a kind of defect: drop a `case` clause, shift `>=` to `>`, swap `and` for `or`, … |
| **reni** | an isolated workspace where wekufe are cast. Your working tree is never touched |

Reports use plain words (`killed`, `survived`, `timeout`) so a CI log reads without the glossary.

## Languages

| Language | kalku | How |
|---|---|---|
| Elixir | native | kalku owns the loop: warm BEAM nodes, in-memory loading, coverage-based test selection |
| kaikai | native (thin) | built on `kai mutate`; this is how kalku tests itself |
| Rust | native | sites come from `syn`, Rust's own parser; the **2024 edition only** (Rust 1.85 or later). It finds sites and casts them, answers `abort` in place, and selects tests by coverage where the LLVM tools are |
| Python | native | sites come from `ast` and `tokenize`, Python's own parser; it runs in the **project's own interpreter** (3.12 or later) with the standard library only. Every run of the suite is a forked child, so `abort` is a kill and a wekufe never leaks; coverage per test comes from `sys.monitoring`; the project's own pytest options are kept |
| others | driver | wraps an existing framework (Stryker, PIT, …), normalizes its results, and recomputes the score |

## What works today

| Piece | State |
|---|---|
| **`kalku init`** — detects the language, writes `.kalku.toml` and the summoning script | done |
| **`kalku run <files>`** — measures those files and reports survivors | done |
| **`kalku cast <wekufe>`** — re-cast one wekufe, and `kalku show` to read it back | done |
| **`kalku propose-equivalent`** — an agent proposes, a person accepts | done |
| **`kalku info`** — spells, outcomes, formats and the agent loop, in the binary | done |
| **`kalku mcp`** — the four agent tools, over stdio | done |
| **`kalku serve`** — the client protocol on a `0600` Unix socket, streaming a run as it happens | done: one client at a time |
| **Warm sessions** — the pool a project was measured with survives the run, and is replaced when the project changes | done |
| **Protocol** — both directions, canonical NDJSON, fixtures for every message | done |
| **Core** — planner, test selection, outcomes, score, gate, equivalents, `.kalku.toml`, sharding, changed lines | done |
| **Reports** — human, JSON, GitHub annotations, and `agent` NDJSON with per-spell hints | done |
| **Orchestrator** — kalku pool, scheduler, timeout escalation (abort → reset → kill), restart budget, cancellation | done |
| **Elixir kalku** — sites, `prepare`, `baseline` with per-test coverage, `cast`, `abort`, `reset`, `reload` | done: every protocol message but `delegate` |
| **kaikai kalku** — the one kalku measures itself with | done |
| **Rust kalku** — sites for `arm`, `compare`, `connect`, `negate`, `literal` from `syn`, behind the kalku protocol | done: finds sites |
| Rust kalku: `prepare`, `baseline` and `cast` in the reni, `abort`, per-test coverage with LLVM | done |
| Rust kalku: `kalku init` for Cargo projects, shipped in the release | done |
| **Python kalku** — sites from `ast`, `prepare`/`baseline`/`cast` in forked children, `abort`, per-test coverage with `sys.monitoring`, `kalku init`, shipped in the release | done |
| **Per-test coverage used by a run** — a wekufe is cast against the tests that reach it | done: Elixir, Rust, Python |
| Parallel workers in an Elixir project (one build path, so one worker) | not yet |
| **`--since <ref>`** — measure what a change touched, and block on holes it introduced | done |
| `--watch` and `--ci` | not yet |
| `merge` — combining sharded reports | not yet |
| **`kalku_elixir` on Hex** — the Elixir kalku installs like any dependency | done |
| **Release binaries** — `brew install`, or a tarball per platform, built and checksummed by CI | done |
| macOS on Intel, Linux on arm64 (waiting on a kaikai toolchain for them) | not yet |

Both kalku are driven end to end by their own test suites, over real pipes, against real fixture projects — and kalku measures its own suite through the kaikai one (`make self-mutate`).

## In CI

A pull request is gated on what **it** introduced, not on a global score
that punishes whoever touches a file with old debt:

```sh
kalku run --since origin/main
```

That measures only the lines the change touched, and exits `1` when it
introduced a hole: a wekufe that survived on one of those lines, or code on
one that no test reaches at all. Old debt elsewhere in the same file does
not block it. Exit codes separate *"your tests have holes"* (`1`) from
*"kalku could not measure"* (`2`), and `2` is what you get when git cannot
tell what changed — a gate that read that as *"nothing changed"* would pass
every pull request it could not read.

The change is measured from where the branch left `origin/main`, and the
working tree counts, so a change still being written is judged the way it
will be committed. A CI checkout needs enough history to find that point
(`fetch-depth: 0` for `actions/checkout`).

A run asked about a change casts all of it. If you pass `--limit` and it
cuts the change short, a clean result is not an answer, and the run exits
`2` rather than passing over lines nobody looked at.

## Documentation

- [`docs/design.md`](docs/design.md): architecture, run lifecycle, outcomes and score, spells, equivalent mutants, CI, distribution.
- [`docs/protocol.md`](docs/protocol.md): the kalku and client protocols.
- [`CLAUDE.md`](CLAUDE.md) and [`.claude/rules/`](.claude/rules/): project principles and conventions.

## Building

Requires [kaikai](https://github.com/kaikailang-org/kaikai) (version in `.kaikai-version`), plus [`km`](https://github.com/lnds/kimun) and `jq` for the quality gate.

```sh
make build     # _build/kalku
make test      # kaikai tests, and the Elixir kalku's
make ci        # kaikai side: format check, lint, build, tests, km quality gate
make check     # everything: `make ci` plus the Elixir kalku's tests
```

### Releasing

`cz bump` writes the version and the tag; pushing the tag is what
releases. `.github/workflows/release.yml` then builds a tarball on each
platform's own runner — nothing cross-compiles — unpacks it and runs the
binary before publishing, and attaches the tarballs, their checksums and
the Homebrew formula to the release.

```sh
cz bump
git push --follow-tags && git push origin "v$(cat VERSION)"   # cz tags are lightweight
```

The formula is generated by `tools/brew-formula.sh` from the checksums of
the files being published, so it cannot name a tarball it did not see.
The tap in [`lnds/homebrew-kalku`](https://github.com/lnds/homebrew-kalku)
picks it up from the release assets with its own token, on a daily schedule, so
the formula can trail a release by up to a day — there is no
credential for another repository kept here.

`make dist` builds the same tarball locally, for this machine's platform.

The tag publishes both halves: the binaries here, and `kalku_elixir` to
Hex from the same version (`HEX_API_KEY` as a secret; without it the step
says so rather than failing). They are installed separately, so a version
they do not share is a version someone has to reconcile — and the
handshake refuses a mismatch by naming which half to move.

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

## License

Licensed under either of [Apache License, Version 2.0](LICENSE-APACHE) or
[MIT license](LICENSE-MIT) at your option — `MIT OR Apache-2.0`, the same
terms as kaikai. Apache-2.0 is there because it grants patent rights
explicitly, which is what a company's review asks for; MIT is there
because it is short. Unless you state otherwise, a contribution you
submit for inclusion in kalku is dual licensed on those terms, with no
additional conditions.

## Contributing

[`CONTRIBUTING.md`](CONTRIBUTING.md) has the whole of it. The short
version: open an issue before anything larger than a fix, one pull
request does one thing, every fix brings a fixture that reproduces the
bug, `make ci` has to pass, and code and commits are in English with
[Conventional Commits](https://www.conventionalcommits.org/).
