# kalku-go

The Go kalku: it finds the places where a defect can be cast in a Go module,
casts them, and says which ones the module's tests notice.

It speaks [the kalku protocol](../../docs/protocol.md) and does what a kalku is
for: it knows the language. Planning, scheduling, caching and scoring belong to
the kaikai side. The design is in [`docs/design.md`](../../docs/design.md).

## Using it

```sh
cd my-module           # where go.mod is
kalku init             # writes .kalku.toml and .kalku/summon
kalku run internal/parser/scan.go
```

`kalku-go` has to be on the `PATH` (it ships in the release tarball and in
`brew install lnds/kalku/kalku`). It runs the `go` on the `PATH`, in the
module, so the toolchain is the one the module's `go.mod` asks for: Go 1.21 or
later.

## What it does

- **Sites** from `go/parser`: `compare`, `connect`, `negate` (an `if` or `for`
  condition), `literal` (decimal integers and booleans), `arm` (a clause of a
  `switch`, never `default` or the last) and `call` (a call that is a whole
  statement). Test files, generated files, files the build leaves out on this
  machine, and the arguments of the calls `exclude_calls` names are never
  touched.
- **Casts** in a copy of the module in the reni, through `go test -overlay`:
  the wekufe is written beside the copy and never into it, so nothing needs
  undoing and a killed kalku leaves the copy as it was.
- **Runs** `go test -json` and counts each test's own verdict. Only the
  packages whose test binary holds the mutated package run anew; the others'
  binaries are unchanged, and `go test` may answer for them from its cache.
- **`abort`** ends the cast's process group, and the kalku takes the next
  request.

## Limits worth knowing

- No per-test coverage yet: every wekufe faces the whole suite, cut down to the
  packages that can see it.
- One module, from its root, with `GOWORK=off`: a `go.work` around it is not
  used, and a module nested inside it is not measured with it.
- Without type information, a literal is changed by its text: an integer that
  is the largest of a sized type steps down instead of up, and a constant used
  as an array's length elsewhere becomes a compile error.

## Developing it

```sh
cd adapters/go
gofmt -l . && go vet ./... && go test ./...
UPDATE_GOLDEN=1 go test ./kalku -run Golden   # then read the diff
```
