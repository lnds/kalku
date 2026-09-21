# kalku

**Mutation testing for humans, CI, and coding agents.** Native where a language has none, and one honest gate over the tools that already exist.

kalku checks how good a test suite really is by breaking the code on purpose, one small defect at a time, and seeing whether any test notices. A defect that no test catches is a hole in the suite, and kalku reports it with a file, a line, and a diff.

Coding agents write tests fast, but a test can run a line without checking anything about it. Coverage can't catch that; mutation testing can. **Agents write tests fast; kalku tells them whether those tests test anything.**

It targets Elixir first. It is written in [kaikai](https://github.com/lnds/kaikai) and uses kalku to test its own code.

> **Status: pre-alpha.** The design is written; the code is not. Nothing below works yet. Track progress in the [issues](https://github.com/lnds/kalku/issues).

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

## Planned usage

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
- **MCP:** `kalku serve` exposes `kalku_run`, `kalku_cast`, `kalku_show`, and `kalku_propose_equivalent` as tools for Claude Code, Cursor, and similar hosts.
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

## Contributing

- Code, docs, and commits are in English.
- Commits follow [Conventional Commits](https://www.conventionalcommits.org/): `feat(elixir): …`, `fix(scheduler): …`. Versions and `CHANGELOG.md` are generated by [commitizen](https://commitizen-tools.github.io/commitizen/) (`cz bump`). Do not edit them by hand.
- Pending work lives in GitHub issues.
