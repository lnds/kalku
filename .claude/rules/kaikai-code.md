---
paths:
  - "**/*.kai"
---

# Writing the kaikai side

## `kai info` is authoritative

Before writing a kaikai snippet, run `kai info syntax` and the relevant topic (`kai info effects`, `kai info match`, `kai info actors`, …). It lists the forms kaikai has and, under **NOT IN KAIKAI**, the plausible false friends it does not (`\x -> body`, `do { }`, `return`, `throw/catch`, …). Do not extrapolate from Haskell, Elixir, or Rust.

## Reuse the stdlib before writing helpers

`os/process` (summoning kalku), `fs`, `encoding/json`, `net/tcp` + `net/http` (server), actors with supervision (the kalku pool), fibers (parallelism). If something is missing or broken in the stdlib, open an issue on kaikai with a repro instead of working around it silently here.

## Code-quality bar

Every file scores `km score` **A− or better; B is the hard floor.**

- < 400 LOC target, < 800 hard cap. Split before growing.
- `km cogcom`: average < 5/fn, max < 25/fn. A high number is a design smell.
- No new duplicate groups (`km dups`).

## Effects are the architecture

Side effects (process, fs, net, clock) enter through effect rows so the planner, scheduler, and scorer can be tested with pure handlers. Logic that decides *what* to do stays free of `Process`/`NetTcp`; only the edges perform them.

## Comments

- If the code is clear, no comment.
- Brief and timeless: no issue numbers, dates, or phase names.
- Document invariants and traps, not narrative.
- Every `pub` symbol carries a `#[doc]` whose first line is a one-sentence synopsis.
