---
paths:
  - "**/*.kai"
---

# Writing the kaikai side

kalku is written in kaikai and is meant to read like an example of the
language used well, not like another language transcribed into it.

## `kai info` is authoritative

Before writing a kaikai snippet, run `kai info syntax` and the relevant topic (`kai info effects`, `kai info match`, `kai info actors`, …). It lists the forms kaikai has and, under **NOT IN KAIKAI**, the plausible false friends it does not (`\x -> body`, `do { }`, `return`, `throw/catch`, …). Do not extrapolate from Haskell, Elixir, or Rust.

`kai doc <module>.<symbol>` is cheaper than a topic page when the question is one signature. For the language's own reasoning — why a form exists, when to reach for it — *The kaikai book* is the long form; chapter numbers below refer to it.

## Reuse the stdlib before writing helpers

`os/process` (summoning kalku), `fs`, `encoding/json`, `net/tcp` + `net/http` (server), actors with supervision (the kalku pool), fibers (parallelism). If something is missing or broken in the stdlib, open an issue on kaikai with a repro instead of working around it silently here.

## The four guarantees

kaikai offers four ways to state that code is right, and they are not
redundant — each catches a class of bug the others miss (book ch. 11.6).
A module that deserves trust uses all four:

| Mechanism | States | Checked |
|---|---|---|
| **Types** — sum types, exhaustive `match` | every case of a value is covered | compile time |
| **Contracts** — `requires` / `ensures`, `T where P` | what an operation demands and promises | compile time when provable, runtime otherwise |
| **`test`** | for *this* input, the output is *that* | `kai test` |
| **`check`** | for *every* input, this invariant holds | `kai check`, with generated values and shrinking |

Write them in that order of discovery: a `test` for the case at hand,
more `test`s for the edges, a `check` once several tests are visibly the
same invariant with different data, a `bench` only when speed is in
question. A `check` written before the invariant is understood passes by
accident and proves nothing.

All four cost nothing at runtime when they hold: a contract the compiler
proves emits no assert, and tests do not ship.

### Where a `check` earns its place

Universal statements: round-trips (`decode(encode(x)) == x`),
idempotence, conservation (a plan's shards contain every wekufe exactly
once), monotonicity, and independence (how a stream is cut into chunks
must not change the lines framed out of it). `check` v1 generates only
`Int`, `Bool`, `Char`, `String` and lists of those, so a property over a
domain type is expressed by deriving the value from an `Int` — and a
property over a protocol message usually belongs in a fixture instead.

### Contracts are for our own invariants, never for a user's input

`requires k >= 1` on a sharder, `ensures result >= floor` on a timeout:
these state what this code guarantees itself, and a violation is a bug
here, so a panic with the offending value is the right outcome.

Anything read from a user's project — `.kalku.toml`, a kalku's replies,
a `sites.ndjson` — is not an invariant but input, and input is validated
into a `Result` whose error names the key and the reason. A contract
there would turn someone's typo into a crash, which is the opposite of
what a tool should do with it.

A contract has a second use here worth naming: it is an oracle that kills
wekufe without a test being written for it, and honestly so, because it
is a real assertion in shipped code.

## Effects are the architecture

Side effects (process, fs, net, clock) enter through effect rows so the
planner, scheduler, and scorer can be tested with **handlers**, not with
real processes. Logic that decides *what* to do stays free of
`Process`/`NetTcp`; only the edges perform them.

### A handler is how effectful code is tested

There is no mocking in kaikai and none is needed: the nearest `handle …
with Eff` wins over an effect's default handler, including for the
builtins (book ch. 12.4, 12.11). A `Process` handler that answers from a
script tests the scheduler's state machine with no child processes, no
pipes and no clock — deterministic, and as fast as the pure tests:

```kaikai
handle { cast_all(spec, casts) } with Process {
  start_piped(cmd, args, si, so, se, resume) -> resume(Ok(Child { pid: 1 }))
  read_stdout(c, resume) -> resume(Ok(next_scripted_line()))
}
```

`Child` is a plain record (`{ pid: Int }`), so a fake one is `Child { pid: 1 }`.

Testing against a real process is still worth doing — it is the only
thing that proves the protocol works over a real pipe — but it is the
outer ring, not the only ring.

### A resource that must be released belongs in a bracket

`initially { }` / `finally { }` on a handler run on **every** path out of
the scope: normal return, an outer handler that abandons `resume`, and
cancellation. Cleanup written after a `handle` block runs on none of
those (book ch. 12.8). Anything that must not outlive the run — a
summoned kalku, a reni, a socket — is acquired in `initially` and
released in `finally`, never by a line at the end of a function.

`finally` does not run on `panic`, which aborts the process; surviving a
`kill -9` is the reni's job, not the handler's.

## Idioms worth following

- **Everything is an expression.** `if` and `match` return values; a
  function body is an expression. Bind the result, do not declare a name
  and assign into it from branches.
- **`=` for a direct function, `{ }` for several steps.** The compiler
  takes both; the choice is for the reader.
- **`var x := 0` is sugar over `State`** and does not leak into the
  signature (book ch. 2.2, 12.7). A local counter does not force a
  function to become effectful, so there is no reason to contort a fold
  to avoid one — or to reach for `var` where a fold reads better.
- **Positional tuples `(a, b)`**, not `Pair { fst:, snd: }`.
- **The core stdlib is auto-loaded**: no `import core.*`.
- **`!` propagates** `None`/`Err` through the return type. It never
  raises; it is Rust's `?`, not Elixir's `File.read!`.
- **Pipes**: `|>` applies, `|` maps, `||` flat-maps, `|?` filters. The
  map pipe is not for `Option` — use a `match` or `.map`.

## Code-quality bar

Every file scores `km score` **A− or better; B is the hard floor.**

- < 400 LOC target, < 800 hard cap. Split before growing.
- `km cogcom`: average < 5/fn, max < 25/fn. A high number is a design smell.
- No new duplicate groups (`km dups`).

## Traps this repo has already paid for

Each of these cost a debugging session; none is in `kai info`.

- **Constructor names collide across modules**, and the collision is a
  *runtime* panic (`ambiguous constructor 'Gone'`), not a compile error.
  Check `kalku/wire/enums.kai` before naming a constructor: `Gone`,
  `Casting`, `Phase` and friends are taken.
- **`#[derive(Eq, Show)]` fails on a record holding a type without those
  impls** — `Instant`, `JsonValue`. Drop the derive or keep the field out.
- **A clause-block needs one pattern per parameter**: a two-parameter
  function is `case Ready(m), caps ->`, never `case Ready(m) ->`.
- **`when` is a reserved word**; a binder named `when` fails to parse.
- **`length(s)` on a `String` counts bytes**, not codepoints, which is
  what the protocol's limits are stated in.
- **`argv()` excludes the program name**, so the first argument is `[path]`.
- **An effect row cannot be wrapped onto the next line** in a signature.
- **A lambda that assigns needs a block**: `i => { total := total + i }`.
- **A one-variant sum needs the leading bar**: `type Msg = | Ping`, or
  the name is an alias and not a constructor.
- Names that read as false friends: `enumerate`/`uniq`/`foldl`, not
  `range`/`unique`/`fold`; `repeat` is both `string.repeat` and
  `list.repeat`.

## Comments

- If the code is clear, no comment.
- Brief and timeless: no issue numbers, dates, or phase names.
- Document invariants and traps, not narrative.
- Every `pub` symbol carries a `#[doc]` whose first line is a one-sentence synopsis.
