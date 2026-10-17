# kaikai kalku

The kalku for kaikai: the one kalku measures itself with. It speaks the kalku protocol on stdio (`docs/protocol.md`) and is deliberately thin — kaikai's own `kai mutate` finds the sites and `kai test` judges them.

## Requirements

kaikai **0.126** or newer. Earlier versions have no machine-readable site listing, which is what a driver needs; `kai mutate --list --json` gained full site data in [kaikai#2157](https://github.com/lnds/kaikai/issues/2157).

## Running

```sh
adapters/kaikai/bin/kalku-kaikai      # protocol on stdin/stdout
```

Built by `make test-kaikai`, or on its own:

```sh
kai build ./adapters/kaikai -o adapters/kaikai/bin/kalku-kaikai
```

## The workspace

`prepare` copies the package into `<reni>/work/<worker>` and warms the build cache there. Every wekufe is spliced into that copy, and every `kai` command runs inside it — including `kai mutate --list`, because it builds a cache where it runs and that cache does not belong in someone's tree.

The package being measured is never written to: not the source, not a build directory. A test asserts it byte for byte.

**A reni inside the package is refused** before anything is copied. Copying a tree into a directory inside itself nests it over and over until the path runs out of room, filling a disk with copies of someone's project on the way there.

## What it claims, and what it does not

`ready` announces `cast`, `per_test_coverage`, `abort`, `cast_after_baseline` and `partial_baseline`. Coverage comes from `kai test --json --coverage`: the baseline credits each line of the package to the tests that ran it, so a wekufe faces those tests, and one in code no test runs is `no_coverage` rather than a survivor. Under an oracle there is no coverage, since an oracle judges as one test. Nothing lives in the process between requests, so the kalku that ran the baseline goes on to cast instead of being banished and another prepared in its place. Not:

| Capability | Why not |
|---|---|
| `hot_load` | every cast rebuilds; there is no warm runtime to splice into |
| `code_hash` | kaikai exposes no hash of the emitted code, so no trivial compiler equivalence and no cache sharing between identical wekufe |
| `reset` | nothing is shared between casts, so nothing can be dirtied |

A baseline is reused whole or run whole. kaikai does not say which files a test is built from, so every test's fingerprint is the package's: every file the copy is made of, the kaikai and kalku versions, and the ids of every test in the suite. A run offered the whole suite with the fingerprint it has now runs none of it; any change to the package, or an offer missing a test, runs all of it. Only a green suite is fingerprinted, none under an oracle, and none in a package with a path dependency or an unlocked one. The files are hashed with `sha256sum`.

A capability is claimed only once it is true. The consequences are worth stating plainly rather than discovering:

- **Every cast rebuilds.** A wekufe costs a build of the package and the tests that reach it. Use `--limit` and a narrow scope.
- **No trivial compiler equivalence.** A wekufe that compiles to the original's code is cast and run like any other.

## An oracle other than `kai test`

A project whose suite is not a set of `test` blocks names the command that judges it, in the `[kaikai]` table of `.kalku.toml`:

```toml
language = "kaikai"

[kaikai]
oracle = "make test-golden"
```

The oracle runs in the copy, through `/bin/sh`, and is judged by its exit status alone, as `kai mutate --oracle` judges it: 0 means healthy, any other status means the wekufe was caught, and a signal means nothing judged it (`crashed`). It is the whole suite of the baseline, as one test named by the command.

What it costs:

- **The whole oracle runs for every wekufe**: an exit status has no tests in it to select from.
- **A typecheck before each oracle.** An exit status cannot tell a wekufe that does not compile from one a test caught, so the copy is typechecked first (`kai typecheck <file>`) and a failure there is a `compile_error`. What a typecheck cannot see, a backend gap, still reaches the oracle and is counted as a kill.

Any other key in the table is refused when the kalku is greeted, so a misspelt `oracle` does not measure quietly with `kai test`.

## Sites

Sites come from `kai mutate --list --json`, which is kaikai's own parser rather than a text scan. Each carries the span in bytes on both ends, the original and replacement text, and the enclosing declaration with an ordinal — so diffs are real and a declared equivalent keyed by meaning survives an edit above it.

The splice is done here rather than through `kai mutate --apply`, since the site already carries the span and the replacement: one fewer process per cast, and the same bytes either way.

## Measuring kalku with kalku

```sh
make self-mutate
```

Until `kalku run` exists this drives kaikai's `kai mutate` directly — the same engine this kalku wraps, so the survivors are the same ones found the long way round. Advisory: it reports and never fails a build, as *Self-hosting* in `docs/design.md` asks until the first release exists.
