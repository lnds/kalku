---
paths:
  - "adapters/**"
---

# Writing a kalku for a language

A kalku knows one language's dark arts and nothing about scheduling, caching, or scoring. It lives under `adapters/<language>/` and speaks `docs/protocol.md`.

A kalku is either **native** (`cast`: kalku owns the loop) or a **driver** (`delegate`: an existing framework owns it). The rules below are for native kalku; a driver only runs its framework, normalises each status per the table in `docs/design.md`, keeps the original in `tool_status`, and never forwards the framework's score.

The kaikai kalku is native but thin: it drives `kaic2 --mutate-list-json` / `--mutate-apply` and `kai test`. What it is missing belongs in kaikai as an issue, not as a workaround here.

## Responsibilities — and only these

1. **Sites.** Parse source with the language's own parser (Elixir: `Code.string_to_quoted/2` with `columns: true, token_metadata: true`) and emit sites: file, span, spell, replacement text.
2. **Cast.** Splice a wekufe into a warm runtime (Elixir: compile the spliced module in memory) without touching files in the user's project.
3. **Run.** Execute exactly the tests the kaikai side asks for and report the outcome. The kaikai side enforces timeouts from outside; a kalku does not time itself.
4. **Report.** Answer with protocol messages only. Nothing else goes to stdout; logs go to stderr.

## Site rules

- The AST decides *what* and *where*; the replacement is a **token span** in the original source, so a wekufe differs by one defect alone and keeps the file's formatting.
- Never propose sites inside test files, string contents, comments, or docs.
- Do not propose sites whose wekufe are equivalent by construction (text that only reaches logs or docs, `if` with identical branches, calls matched by `exclude_calls`). Each such rule has a fixture.
- Every site carries its semantic key (`enclosing`, `ordinal`) so declared equivalents survive edits.
- Spells use the shared names (`arm`, `compare`, `connect`, `negate`, `literal`, `call`). A language-specific spell gets a new shared name through the protocol, not an ad-hoc string.

## Isolation

- A wekufe never outlives its cast: the next cast starts from the original modules.
- A kalku reports `equivalent` only with mechanical evidence (identical compiled code). It never guesses equivalence.
- If the kalku cannot guarantee its runtime is restored (the wekufe touched global state), it answers `dirty: true`; the kaikai side sends `reset` first and recycles only if that fails.
- On `abort`, stop the cast's work inside the runtime and restore; never exit to handle a timeout.

## Quality

- Follow the target language's idioms and its standard formatter/linter (Elixir: `mix format`, Credo).
- Keep the kalku small. If it grows logic the kaikai side could own, move it there.
- Each spell has fixtures: a source sample and the exact sites it must produce.
