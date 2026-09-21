# kalku

Mutation testing for humans, CI, and coding agents: native where none exists — Elixir first — and one honest gate over the mutation frameworks that already exist elsewhere. Later, synthesised property testing. The core is written in **kaikai**; each target language contributes a small **kalku**. kalku measures its own suite through its kaikai kalku.

Full design in `docs/design.md`; wire format in `docs/protocol.md`.

## Project language

- Code, commit messages, PR titles/bodies, and all documentation are **English only**, even when the briefing is in Spanish.
- Conversation with the user (Spanish) is not documentation and does not appear in the repo.

## The shape of the system

| Piece | Role | Written in |
|---|---|---|
| **kaikai side** | plans which wekufe to cast, schedules them across kalku, caches, selects tests by coverage, enforces timeouts, scores, reports. Runs as a CLI or as a long-lived server — the same code either way. | kaikai |
| **kalku** | a worker bound to one language. **Native** (Elixir, kaikai): finds sites with the language's own tooling, casts a wekufe into a warm runtime, runs the selected tests. **Driver**: runs an existing framework and normalises its statuses. | the target language |
| **reni** | a project's isolated workspace (build artefacts, caches). Wekufe never touch the user's tree. | — |
| **protocol** | versioned NDJSON between the kaikai side and each kalku, transport-agnostic. | `docs/protocol.md` |

## Layout

```
main.kai            entry point
kalku/              the kaikai side; `kalku/<dir>/<file>.kai` imports as `kalku.<dir>.<file>`
  wire/             protocol types, codec, framing (`protocol` is a kaikai keyword)
  core/             pure logic
  orchestrator/     kalku pool, scheduler, server
tests/              kaikai tests, one `*_test.kai` per module
adapters/<lang>/    one kalku per language
tools/              repo scripts (km-gate.sh)
```

`make ci` runs everything CI runs. `kai fmt .` does not walk the package; use `make fmt` / `make fmt-check`. The kaikai version is pinned in `.kaikai-version`; `cz bump` keeps `VERSION`, `kai.toml`, and `kalku/version.kai` in sync.

## Vocabulary

Use the glossary in `docs/design.md` consistently in code, docs, and protocol: **kalku** (language worker), **wekufe** (mutant), **spell** (mutation operator), **site**, **reni** (isolated workspace), **summon** / **banish** (start / stop a kalku), **cast** (run one wekufe). Do not mix in synonyms (`mutant`, `operator`, `adapter`, `worker`) where a glossary word exists.

Outcomes keep plain technical names — `killed`, `survived`, `timeout`, `no_coverage`, `compile_error`, `crashed`, `equivalent` — because they appear in CI logs and must read without the glossary.

## Principles

Higher tier wins on conflict.

### Tier 1 — Load-bearing

1. **Honest results.** A score is only worth something if it can be trusted. A wekufe differs from its parent by one defect alone. A survivor is a real hole with a file, a line, and a diff. Timeouts, compile errors, and crashes are their own categories — never counted as kills. Equivalent-wekufe suppressions carry a written reason or are a hard error.
2. **Never harm the user's project.** Wekufe are cast in memory or inside the reni, never in the user's working tree. A crash, a `kill -9`, or a hung kalku leaves the repo exactly as it was.
3. **Speed is the product.** Mutation testing that takes an hour does not get run. Warm kalku, coverage-based test selection, incremental runs, and parallelism are core design, not later optimisation. A warm kalku is precious: abort and reset in place; kill a process only when it stops answering.

### Tier 2 — Aspirational

4. **Language-agnostic core.** Nothing specific to one language lives on the kaikai side. If the core needs to know about Elixir, the protocol is missing a concept.
5. **Survivors first, structured always.** Human reports lead with survivors (file, line, spell, diff); the score is context and trend. Every result is also available as stable JSON.
6. **Kalku stay thin.** A kalku parses, casts, runs, and reports. Planning, scheduling, caching, and scoring belong to the kaikai side.
7. **Agents are first-class clients.** Coding agents iterate: find a survivor, write a test, re-cast, repeat. Serve that loop (`--format agent`, `kalku cast`, MCP) and never let an agent hide a hole instead of closing it: agents only *propose* equivalents, suppression changes are always loud, and a kill needs a test that passes on the original code.

### Tie-breakers

- Honesty beats speed.
- Not harming the user's project beats everything.
- A missing protocol concept beats a language special case in the core.

## Detailed rules

Topic rules live in `.claude/rules/` and load by path:

- `kaikai-code.md` — writing the kaikai side.
- `adapters.md` — writing a kalku for a language.
- `protocol.md` — changing the kalku or client protocol.
- `security.md` — execution isolation and the server surface.
- `git.md` — commits, PRs, changelog.

## Working discipline

- **Think before acting; don't over-complicate.** Pick the direct path.
- **Use what's already in context.** Don't re-run commands for facts already known.
- **Measure before optimising.** Performance claims come with numbers from a real project.

## Things to avoid

- **Do not make kaikai depend on kalku.** kalku is written in kaikai; kaikai's CI keeps its own `kai mutate` driver. The two share the site format, never code, or the bootstrap becomes circular.
- **Do not work around kaikai gaps here.** If the kaikai kalku needs something from `kaic2` or `kai test`, open an issue on kaikai.
- **Do not self-host with the binary under test** once a release exists: kalku measures itself with the last release, pinned.
- **Do not write a parser for a target language in kaikai.** Sites come from the target's own parser, through its kalku.
- **Do not scan source text for sites.** A regex that mutates a string literal or a comment produces noise, not measurements.
- **Do not cast spells on test code.** Mutating the oracle is not a measurement of the oracle.
- **Do not generate hints with a model.** Survivor hints are fixed templates per spell, filled from the site: deterministic and honest about what kalku knows.
- **Do not reinvent what a target ecosystem already does well** (e.g. StreamData generators in Elixir). kalku adds what is missing.
