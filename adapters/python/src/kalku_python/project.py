"""The project in the reni.

The project is copied there, so that nothing a run does touches the user's
tree, and the copy is kept up to date by content. Python records a source's
modification time and size inside the `.pyc` it compiles, so a copy that kept
the original's time next to a bytecode cache of an earlier version of the
same file could be trusted as up to date: a file whose content differs is
written with a time of now, an identical one is left alone, and what the
project no longer has is removed. No bytecode is ever copied or written.
"""

from __future__ import annotations

import fnmatch
import os
import shutil
from pathlib import Path, PurePosixPath

# What a build, a virtual environment or version control leaves: not the
# project, and often more files than the project itself.
IGNORED_DIRS = frozenset(
    {
        ".git",
        ".hg",
        ".svn",
        ".venv",
        "venv",
        "env",
        "__pycache__",
        ".tox",
        ".nox",
        ".mypy_cache",
        ".pytest_cache",
        ".ruff_cache",
        ".hypothesis",
        "node_modules",
        "build",
        "dist",
        ".eggs",
        "site-packages",
    }
)

_IGNORED_GLOBS = ("*.egg-info", "*.pyc", "*.pyo")

# The directories whose files are the suite, and the names of files that are.
TEST_DIRS = frozenset({"tests", "test"})


def is_ignored(name: str) -> bool:
    return name in IGNORED_DIRS or any(fnmatch.fnmatch(name, g) for g in _IGNORED_GLOBS)


def is_test_file(relative: str) -> bool:
    """True when a path is part of the suite, which is the oracle and is never
    measured: under a tests directory, or named like a test."""
    path = PurePosixPath(relative.replace(os.sep, "/"))
    name = path.name
    if any(part in TEST_DIRS for part in path.parts[:-1]):
        return True
    return (
        name == "conftest.py"
        or (name.startswith("test_") and name.endswith(".py"))
        or name.endswith("_test.py")
    )


def sync_tree(source: Path, dest: Path, skip: Path | None = None) -> None:
    """Bring `dest` up to date with `source`, without what a build leaves.

    `skip` is left out in case the reni is inside the project."""
    dest.mkdir(parents=True, exist_ok=True)
    wanted = set()
    with os.scandir(source) as entries:
        for entry in entries:
            if is_ignored(entry.name):
                continue
            path = Path(entry.path)
            if skip is not None and path == skip:
                continue
            wanted.add(entry.name)
            target = dest / entry.name
            if entry.is_symlink():
                _sync_link(path, target)
            elif entry.is_dir():
                if target.is_symlink() or target.is_file():
                    target.unlink()
                sync_tree(path, target, skip)
            else:
                _sync_file(path, target)
    for existing in list(dest.iterdir()):
        if existing.name not in wanted:
            _remove(existing)


def _sync_link(source: Path, target: Path) -> None:
    link = os.readlink(source)
    if target.is_symlink() and os.readlink(target) == link:
        return
    _remove(target)
    os.symlink(link, target)


def _sync_file(source: Path, target: Path) -> None:
    data = source.read_bytes()
    if target.is_file() and not target.is_symlink() and target.read_bytes() == data:
        return
    _remove(target)
    target.write_bytes(data)
    shutil.copymode(source, target)


def _remove(path: Path) -> None:
    if path.is_symlink() or path.is_file():
        path.unlink()
    elif path.is_dir():
        shutil.rmtree(path)


def import_roots(work: Path) -> list[str]:
    """Where the project's packages are imported from: the project itself and,
    in the `src` layout, the directory under it."""
    roots = [str(work)]
    if (work / "src").is_dir():
        roots.insert(0, str(work / "src"))
    return roots


def project_packages(work: Path) -> set[str]:
    """The top-level names the project defines: what has to be imported again,
    from the copy, whatever else has already been imported by that name."""
    names = set()
    for root in import_roots(work):
        try:
            entries = list(os.scandir(root))
        except OSError:
            continue
        for entry in entries:
            if is_ignored(entry.name) or entry.name.startswith("."):
                continue
            if entry.is_dir() and (
                os.path.exists(os.path.join(entry.path, "__init__.py"))
                or any(f.name.endswith(".py") for f in _safe_scandir(entry.path))
            ):
                names.add(entry.name)
            elif entry.is_file() and entry.name.endswith(".py"):
                names.add(entry.name[:-3])
    return names


def _safe_scandir(path: str):
    try:
        return list(os.scandir(path))
    except OSError:
        return []
