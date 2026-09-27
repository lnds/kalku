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

`ready` announces `cast` and `abort`. Not:

| Capability | Why not |
|---|---|
| `hot_load` | every cast rebuilds; there is no warm runtime to splice into |
| `per_test_coverage` | kaikai reports no per-test coverage, so **every test runs for every wekufe** |
| `code_hash` | kaikai exposes no hash of the emitted code, so no trivial compiler equivalence and no cache sharing between identical wekufe |
| `reset` | nothing is shared between casts, so nothing can be dirtied |

A capability is claimed only once it is true. The consequences are worth stating plainly rather than discovering:

- **Runs are slow.** With no coverage to select by, a wekufe costs a whole suite. Use `--limit` and a narrow scope.
- **No trivial compiler equivalence.** A wekufe that compiles to the original's code is cast and run like any other.

## Sites

Sites come from `kai mutate --list --json`, which is kaikai's own parser rather than a text scan. Each carries the span in bytes on both ends, the original and replacement text, and the enclosing declaration with an ordinal — so diffs are real and a declared equivalent keyed by meaning survives an edit above it.

The splice is done here rather than through `kai mutate --apply`, since the site already carries the span and the replacement: one fewer process per cast, and the same bytes either way.

## Measuring kalku with kalku

```sh
make self-mutate
```

Until `kalku run` exists this drives kaikai's `kai mutate` directly — the same engine this kalku wraps, so the survivors are the same ones found the long way round. Advisory: it reports and never fails a build, as *Self-hosting* in `docs/design.md` asks until the first release exists.
