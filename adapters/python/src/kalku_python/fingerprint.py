"""What the tests of one test file run, as one string that changes when any
of it does.

The unit is the test file: what its tests share, a module as it is imported
and a fixture of the module's scope, happens once for all of them, so a file
runs whole or not at all.

A test file runs itself, the `conftest.py` files on its path, the packages
those are in, and what they import, directly or through others, as the
import graph reads it from the syntax. Each goes in by its path and what is
in it, with the files kept beside it that are not Python: the data its code
reads. What is not the
project's own goes in by the versions of everything installed, of the
interpreter, of pytest and of this kalku; and what any test can reach without
importing it, by the files that configure the tools and the dependencies,
and every file kept in the suite's directories that is not Python.

An import the syntax does not show, a name given to `importlib` or a plugin
loaded by its entry point, would be missed. So a run says which of the
project's modules it imported, and those no file's closure explains go into
every fingerprint, named in it so that the next run can take them again; and
a file whose tests were seen to execute a source its fingerprint does not
cover is given none, and always runs.

What the project keeps at its top level counts only through those
configuration files: a README there is not an input to any test.
"""

from __future__ import annotations

import hashlib
import json
import os
import sys
from importlib import metadata
from pathlib import Path, PurePosixPath

from . import __version__, dependency, project

CONFIGURATION = (
    "pyproject.toml",
    "setup.py",
    "setup.cfg",
    "pytest.ini",
    ".pytest.ini",
    "tox.ini",
    "uv.lock",
    "poetry.lock",
    "pdm.lock",
    "Pipfile",
    "Pipfile.lock",
)

# Past this many modules imported in ways the syntax does not show, a
# fingerprint that names them all is not worth carrying, and none is given.
MAX_UNEXPLAINED = 64

_SEPARATOR = "|"


def _is_configuration(name: str) -> bool:
    return (
        name in CONFIGURATION
        or name.startswith(".env")
        or (name.startswith(("requirements", "constraints")) and name.endswith((".txt", ".in")))
    )


def environment() -> str:
    """The interpreter, pytest, this kalku, and every distribution installed."""
    import pytest

    installed = sorted(
        f"{d.metadata.get('Name', '')}=={d.version}" for d in metadata.distributions()
    )
    words = [
        f"kalku-python {__version__}",
        f"{sys.implementation.name} {sys.version}",
        f"pytest {pytest.__version__}",
        f"KALKU_PYTEST_ARGS={os.environ.get('KALKU_PYTEST_ARGS', '')}",
        *installed,
    ]
    return "\n".join(words)


class Prints:
    """The fingerprints of the test files of a copy, worked out once per baseline."""

    def __init__(self, work: Path, graph: dependency.Graph, words: str):
        self.work = work
        self.graph = graph
        self.present = set(graph.imports)
        self._hashes: dict[str, str] = {}
        self._covers: dict[str, frozenset[str]] = {}
        self._data: dict[str, frozenset[str]] = {}
        shared = {f for f in self._top_level() if _is_configuration(f) and (work / f).is_file()}
        for directory in project.TEST_DIRS:
            if (work / directory).is_dir():
                shared |= self._data_under(directory, whole=True)
        self.shared = frozenset(shared)
        self._base = self._digest(words, self.shared)

    def covers(self, file: str, unexplained: tuple[str, ...] = ()) -> frozenset[str]:
        """The files a test file's fingerprint covers."""
        roots = [file, *self._conftests(file)]
        own = self._cover(file, [*roots, *(p for r in roots for p in self._packages_above(r))])
        for f in unexplained:
            own = own | self._cover(f"imported {f}", [f])
        return own

    def _cover(self, key: str, roots: list[str]) -> frozenset[str]:
        if key not in self._covers:
            code: set[str] = set()
            for root in roots:
                code |= self.graph.closure_of(root)
            self._covers[key] = frozenset(code | self._data_beside(code))
        return self._covers[key]

    def of(self, file: str, unexplained: tuple[str, ...] = ()) -> str:
        """A test file's fingerprint, with the modules no closure explains named in it."""
        print_ = self._digest(self._base, self.covers(file, unexplained))
        if unexplained:
            print_ += _SEPARATOR + json.dumps(list(unexplained), separators=(",", ":"))
        return print_

    def explained(self, files: set[str]) -> set[str]:
        """Every file the closures of these test files cover."""
        out: set[str] = set()
        for f in files:
            out |= self.covers(f)
        return out

    # ---- what goes in --------------------------------------------------------

    def _conftests(self, file: str) -> list[str]:
        parts = PurePosixPath(file).parts[:-1]
        found = []
        for i in range(len(parts) + 1):
            candidate = "/".join([*parts[:i], "conftest.py"])
            if candidate in self.present:
                found.append(candidate)
        return found

    def _packages_above(self, file: str) -> list[str]:
        """The `__init__.py` of each package a file is in, which importing it runs."""
        parts = list(PurePosixPath(file).parts[:-1])
        found = []
        while parts and "/".join([*parts, "__init__.py"]) in self.present:
            found.append("/".join([*parts, "__init__.py"]))
            parts.pop()
        return found

    def _top_level(self) -> list[str]:
        try:
            return sorted(e.name for e in os.scandir(self.work) if e.is_file())
        except OSError:
            return []

    def _data_beside(self, code: set[str]) -> set[str]:
        """The files that are not Python in the directories these files are in,
        and in the directories below those that hold no Python: the data the
        code reads. The project's top level is left out."""
        found: set[str] = set()
        for directory in {str(PurePosixPath(f).parent) for f in code}:
            if directory != ".":
                found |= self._data_under(directory)
        return found

    def _data_under(self, directory: str, whole: bool = False) -> frozenset[str]:
        """The files below a directory that are not Python; unless `whole`, only
        down to the directories that hold Python, which are code of their own."""
        key = f"{whole} {directory}"
        if key in self._data:
            return self._data[key]
        found: set[str] = set()
        top = self.work / directory
        for at, names, files in os.walk(top):
            here = Path(at)
            if not whole and here != top and any(f.endswith(".py") for f in files):
                names[:] = []
                continue
            names[:] = [n for n in names if not project.is_ignored(n)]
            for name in files:
                if not name.endswith(".py") and not project.is_ignored(name):
                    found.add((here / name).relative_to(self.work).as_posix())
        self._data[key] = frozenset(found)
        return self._data[key]

    def _digest(self, base: str, files) -> str:
        sum_ = hashlib.sha256(base.encode())
        for f in sorted(files):
            sum_.update(f"\n{f} {self._hash(f)}".encode())
        return sum_.hexdigest()[:24]

    def _hash(self, file: str) -> str:
        if file not in self._hashes:
            try:
                self._hashes[file] = hashlib.sha256((self.work / file).read_bytes()).hexdigest()
            except OSError:
                self._hashes[file] = "unreadable"
        return self._hashes[file]


def unexplained_of(fingerprint: str) -> tuple[str, ...] | None:
    """The modules a fingerprint names as imported where no closure explains
    them, or None when it is not one this kalku gave."""
    _, sep, named = fingerprint.partition(_SEPARATOR)
    if not sep:
        return ()
    try:
        found = json.loads(named)
    except ValueError:
        return None
    if not isinstance(found, list) or not all(isinstance(f, str) for f in found):
        return None
    return tuple(found)
