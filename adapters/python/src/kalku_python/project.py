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


# ---- how pytest is configured -------------------------------------------------

# What `addopts` may say that this kalku cannot honour: running the suite on
# several processes (it forks one child per run), and measuring coverage with
# another tool (the interpreter's own monitor is in use).
_DROPPED_WITH_VALUE = {
    "-n",
    "--numprocesses",
    "--maxprocesses",
    "--dist",
    "--tx",
    "--cov-report",
    "--cov-config",
    "--cov-fail-under",
    "--cov-context",
}
_DROPPED_PLUGINS = {"xdist", "xdist.plugin", "cov", "pytest_cov", "pytest_cov.plugin"}


def read_addopts(work: Path) -> str | None:
    """The `addopts` of the project's pytest configuration, as pytest finds it:
    `pytest.ini`, then `pyproject.toml`, then `tox.ini`, then `setup.cfg`."""
    import configparser
    import tomllib

    ini = work / "pytest.ini"
    if ini.is_file():
        return _from_ini(ini, "pytest", configparser)
    pyproject = work / "pyproject.toml"
    if pyproject.is_file():
        try:
            data = tomllib.loads(pyproject.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            data = {}
        options = data.get("tool", {}).get("pytest", {}).get("ini_options")
        if options is not None:
            value = options.get("addopts")
            return " ".join(value) if isinstance(value, list) else value
    for name, section in (("tox.ini", "pytest"), ("setup.cfg", "tool:pytest")):
        found = work / name
        if found.is_file():
            value = _from_ini(found, section, configparser)
            if value is not None:
                return value
    return None


def _from_ini(path: Path, section: str, configparser) -> str | None:
    parser = configparser.ConfigParser(interpolation=None)
    try:
        parser.read(path, encoding="utf-8")
    except (OSError, configparser.Error):
        return None
    return parser.get(section, "addopts", fallback=None)


def sanitized_addopts(addopts: str | None) -> str:
    """The project's `addopts` without what cannot run here."""
    import shlex

    try:
        words = shlex.split(addopts or "")
    except ValueError:
        return ""
    kept: list[str] = []
    skip_value = False
    for word in words:
        if skip_value:
            skip_value = False
            continue
        name = word.split("=", 1)[0]
        if name in _DROPPED_WITH_VALUE:
            skip_value = "=" not in word
            continue
        if word.startswith("-n") and not word.startswith("--") and len(word) > 2:
            continue  # `-n4`, `-nauto`
        if name == "--cov" or name.startswith("--cov-") or word == "--no-cov":
            continue
        kept.append(word)
    # `-p xdist` takes its value as the next word.
    cleaned: list[str] = []
    i = 0
    while i < len(kept):
        if kept[i] == "-p" and i + 1 < len(kept) and kept[i + 1] in _DROPPED_PLUGINS:
            i += 2
            continue
        if kept[i].startswith("-p") and kept[i][2:] in _DROPPED_PLUGINS:
            i += 1
            continue
        cleaned.append(kept[i])
        i += 1
    return shlex.join(cleaned)
