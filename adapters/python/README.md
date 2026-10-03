# kalku-python

The Python kalku: it finds the places where a defect can be cast in a Python
project, casts them, and says which ones the project's tests notice.

It speaks [the kalku protocol](../../docs/protocol.md) and does what a kalku is
for: it knows the language. Planning, scheduling, caching and scoring belong to
the kaikai side. The design is in [`docs/design.md`](../../docs/design.md).

## Using it

```sh
cd my-project          # where pyproject.toml, setup.py or setup.cfg is
kalku init             # writes .kalku.toml and .kalku/summon
kalku run my_package/core.py
```

`kalku-python` has to be on the `PATH` (it ships in the release tarball and in
`brew install lnds/kalku/kalku`). It runs inside **the project's own
interpreter**, because the tests need the project's own packages and pytest:
the active virtual environment, then `.venv` or `venv` of the project, then
`python3`. Set `KALKU_PYTHON` to choose another.

Python 3.12 or later, and `pytest` installed in that interpreter. macOS and
Linux: every run of the suite is a `fork`.

## What it does

- **Sites** from `ast` and `tokenize`: `compare`, `connect`, `negate`,
  `literal`, `call` and `arm` (a `case` of a `match`). Docstrings, annotations,
  `TYPE_CHECKING` blocks, `__main__` guards, the arguments of the calls
  `exclude_calls` names, and test files are never touched.
- **Casts** in a forked child that is thrown away. The changed module is served
  to the importer from memory, so no file is written and no bytecode cache can
  serve the original. `abort` is a kill of the child's process group.
- **Coverage per test** with `sys.monitoring`, so a wekufe is cast only against
  the tests that reach it. What runs at import is credited to the tests that
  run what it belongs to, and a module's constants and class attributes to the
  tests that depend on the module by what they import.
- **pytest configured by the project.** Its `addopts` are kept, minus `-n`,
  `--dist` and `--cov*`, which cannot run here.

## Knobs

| Variable | Meaning |
|---|---|
| `KALKU_PYTHON` | the interpreter to run in |
| `KALKU_PYTEST_ARGS` | extra arguments for every pytest run, split like a shell |
| `KALKU_PYTHON_DEBUG` | keep the children's stderr, and dump their stacks on `SIGUSR1` |

## Limits worth knowing

- Only pytest. `unittest` suites run under it; other runners do not.
- A module that almost every test imports gets its constants credited to the
  tests that import it themselves, and past a few hundred tests to none that
  did not run its code, because the map the kaikai side reads would otherwise
  be the size of the suite times the module's lines. A wekufe there may be
  judged against fewer tests than depend on it.
- An import that is not written as one (a name given to `importlib`) is not
  seen by the dependency graph.
- A library that keeps global state across the runs of one process may see it
  differently in a forked child; each run starts from the parent's state, so
  nothing leaks from one wekufe to the next, but the first run is the parent's.

## Developing

```sh
python -m venv .venv && .venv/bin/pip install pytest ruff
.venv/bin/python -m pytest
.venv/bin/ruff format . && .venv/bin/ruff check .
```

`tools/neutral_check.py PROJECT FILE...` casts, against a real project, wekufe
that are identical to the original. None may be killed; one that is points at
a test that fails on code nobody changed, or at this kalku.
