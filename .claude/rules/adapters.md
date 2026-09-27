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

## The channel is the kalku's first responsibility

stdout carries the protocol, so a single line of build-system chatter makes the kaikai side banish the worker for writing nonsense — and it does it before the kalku can explain itself.

A kalku can only keep quiet once it is running. Whatever its toolchain prints on the way up was printed before the kalku existed, so the **summoning command** is part of the kalku's contract, not an afterthought: it sends the build's output to stderr and only then execs the loop.

Ship it as an executable in the adapter rather than as a line in a README — a contract nothing enforces is a contract someone will get wrong, and the one who gets it wrong sees a worker banished for writing nonsense. Elixir's is `adapters/elixir/bin/kalku-elixir`, and it refuses to start without a build path inside the reni.

Two rules follow, and they hold for any language:

- **Starting does not build.** Building is what `prepare` is for, and `prepare` reports what happened as a protocol message. A kalku that compiled on the way up would corrupt its own first line, and a project that failed to compile would take the kalku down before it could say why.
- **A build failure is an answer, not an exit.** Catch whatever the toolchain throws — for Elixir, an exit as well as an exception — and reply `error` / `prepare_failed`, fatal, naming the file. A kalku that dies instead leaves the run guessing.

The test that protects this drives a real kalku over a real pipe and asserts that **every** line on stdout parses as JSON. Nothing smaller catches it.

## Site rules

- The AST decides *what* and *where*; the replacement is a **token span** in the original source, so a wekufe differs by one defect alone and keeps the file's formatting.
- Never propose sites inside test files, string contents, comments, or docs.
- Do not propose sites whose wekufe are equivalent by construction (text that only reaches logs or docs, `if` with identical branches, calls matched by `exclude_calls`). Each such rule has a fixture.
- Every site carries its semantic key (`enclosing`, `ordinal`) so declared equivalents survive edits.
- Spells use the shared names (`arm`, `compare`, `connect`, `negate`, `literal`, `call`). A language-specific spell gets a new shared name through the protocol, not an ad-hoc string.

## Count the tests, not the framework's summary

An outcome comes from the individual test results, never from a total the
test runner prints. A runner is free to print one total per module, per
file, or per shard — `kai test --json` prints one per test module — and a
kalku that reads "the" total reads one module and calls it the suite. The
wekufe a later module killed then comes back `survived`, which is the one
error a measuring tool must never make.

A one-module fixture cannot catch this, so every kalku's fixture project
has at least two test modules, with the test that kills sorted *after* a
module that passes.

## Isolation

- A wekufe never outlives its cast: the next cast starts from the original modules.
- A kalku reports `equivalent` only with mechanical evidence (identical compiled code). It never guesses equivalence.
- If the kalku cannot guarantee its runtime is restored (the wekufe touched global state), it answers `dirty: true`; the kaikai side sends `reset` first and recycles only if that fails.
- On `abort`, stop the cast's work inside the runtime and restore; never exit to handle a timeout.

## Quality

- Follow the target language's idioms and its standard formatter/linter (Elixir: `mix format`, Credo).
- Keep the kalku small. If it grows logic the kaikai side could own, move it there.
- Each spell has fixtures: a source sample and the exact sites it must produce.
