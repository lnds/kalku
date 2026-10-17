# kalku-typescript

The TypeScript kalku: it finds the places where a defect can be cast in a
TypeScript or JavaScript project, casts them, and says which ones the project's
tests notice.

It speaks [the kalku protocol](../../docs/protocol.md) and does what a kalku is
for: it knows the language. Planning, scheduling, caching and scoring belong to
the kaikai side. The design is in [`docs/design.md`](../../docs/design.md).

## Using it

```sh
cd my-project          # where package.json and tsconfig.json are
kalku init             # writes .kalku.toml and .kalku/summon
kalku run src/parser.ts
```

`kalku-typescript` has to be on the `PATH` (it ships in the release tarball and
in `brew install lnds/kalku/kalku`). It runs on the `node` on the `PATH`, Node
20 or later, with the project's own Vitest (3 or later) or Jest (29 or later)
from its `node_modules`: install the project's packages first. A project with
no `tsconfig.json` says what it is with `kalku init --lang typescript`.

A project that depends on both Vitest and Jest, and whose `test` script names
neither, says which one to run:

```toml
[typescript]
runner = "vitest"
```

## What it does

- **Sites** from the TypeScript compiler's parser, carried inside the kalku:
  `compare`, `connect`, `negate` (the condition of an `if`, a loop or a
  conditional expression, and a `!` dropped), `literal` (plain decimal
  integers, booleans, non-empty strings), `arm` (a clause of a `switch`, never
  `default` or the last) and `call` (a call that is a whole statement). Types,
  imports, decorators, property names, module specifiers, `case` labels, JSX
  attribute strings, test files, declaration files and tool configuration are
  never touched, nor the arguments of the calls `exclude_calls` names.
- **Casts** in memory: the project's own runner is handed the wekufe as the
  text of its file, through a Vite plugin for Vitest and around the project's
  own transformer for Jest. Nothing is written to the project: caches live in
  the reni, Vitest 5's module cache is off, snapshots are never written, and
  coverage is not collected.
- **A wekufe that was never loaded met no test**, and is `no_coverage`, never a
  survivor: the runner leaves a mark in the reni when it loads it.
- **Coverage per test** from counters the kalku puts in every statement, served
  to the runner in memory in a second run of the suite, with a setup file that
  credits each count to the test running when it happened. What runs outside
  any test, a module as it loads or a `beforeAll`, is credited to every test of
  its file. A test that passes in the first run and not in the counting one
  makes the coverage doubted, and every wekufe it might reach asks the rest of
  the suite too.
- **Runs** only the tests it was asked for, by file and by an exact name
  pattern, and counts each test's own verdict from the runner's JSON report.
- **`abort`** ends the run's process group, workers included, and the kalku
  takes the next request.

## Limits worth knowing

- Code a test reaches only in a worker thread or a process it starts is not
  counted, and the test is not credited with it.
- One package, from its root. A Jest configuration with several `projects` is
  refused. A Vitest configuration with several projects has not been tried: a
  wekufe that does not reach a project's tests is `no_coverage` there, not a
  survivor.
- A test that reaches code only through a process it starts, or through a
  browser, does not load the wekufe: such a cast is `no_coverage`.
- Code a tool loads before the runner's transform (a config file, a global
  setup) cannot be cast in memory, and is not proposed.

## Developing it

```sh
cd adapters/typescript
npm ci
for p in testdata/projects/*/; do (cd "$p" && npm ci); done
npm run format:check && npm run check && npm test
UPDATE_GOLDEN=1 npm test     # then read the diff of testdata/sites
```
