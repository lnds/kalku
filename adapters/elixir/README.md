# Elixir kalku

The native kalku for Elixir: it finds sites with Elixir's own parser and, in later stages, casts wekufe into a warm BEAM node with the project loaded. It speaks the kalku protocol (`docs/protocol.md`) on stdio.

## Status

| Message | Served |
|---|---|
| `hello` → `ready` | yes |
| `sites` → `sites_found` | yes, all six spells |
| `shutdown` → `bye` | yes |
| `prepare`, `baseline`, `cast`, `abort`, `reset`, `reload` | not yet: answered with a non-fatal `bad_request` |

`ready` announces only `cast` (the native kind); no capability is claimed before it works.

## Requirements

- Elixir **1.18** or newer (the built-in `JSON` module), OTP 27 or newer.
- No runtime dependencies: the kalku runs inside the user's project runtime, so it must not bring anything that could conflict with the project's own dependencies.

## Running

```sh
mix compile            # first, so compiler output never reaches stdout
mix kalku.serve        # protocol on stdin/stdout, diagnostics on stderr
```

## Sites

Sources are parsed with `Code.string_to_quoted/2` (`columns`, `token_metadata`, and a literal encoder that keeps positions on literals). The AST decides what and where; the source text only confirms that the expected token sits at the reported position, and a node whose text cannot be pinned down exactly yields no site.

| Spell | Proposes |
|---|---|
| `arm` | delete one clause of `case`, `cond`, `with … else`, `receive`, `fn`, or a multi-clause function; whole lines, only when every clause starts its own line; never the only clause |
| `compare` | `>`↔`>=`, `<`↔`<=`, `==`↔`!=`, `===`↔`!==`, in expressions and guards |
| `connect` | `and`↔`or`, `&&`↔`\|\|` |
| `negate` | `if`↔`unless`; drop `not` / `!` |
| `literal` | integer `n`→`n+1`, `true`↔`false`, `:ok`↔`:error`, a non-empty plain string → `""` |
| `call` | drop one pipe stage; `f(x, …)` → `x` when `x` is a variable or a literal |

Never proposed: test files and scripts (`test/`, anything but `.ex`); `@moduledoc`, `@doc`, typespecs, and other directive attributes; anything inside calls matched by `exclude_calls`; literals inside `raise`; `if`/`unless` whose branches are identical; calls in patterns, guards, or a pipe's right side.

Every site carries its span (line, column in codepoints, byte offset), `original`, `replacement`, `enclosing` (`Module.fun/arity`), and `ordinal`. A candidate whose wekufe would not parse is dropped and counted on stderr.

## Tests

```sh
mix test
```

- `test/protocol_test.exs` — every fixture in `docs/protocol/fixtures/` for this direction decodes and re-encodes byte for byte; invalid fixtures fail with the expected kind.
- `test/sites_test.exs` — one golden per fixture under `test/fixtures/<spell>/`; every site of every fixture and of this kalku's own `lib/` round-trips and parses. After a reviewed change, `KALKU_UPDATE_GOLDENS=1 mix test` rewrites the goldens.
- `test/loop_test.exs` — the protocol loop, request by request.

`test/fixtures/` is excluded from `mix format`: those files are written a particular way on purpose.
