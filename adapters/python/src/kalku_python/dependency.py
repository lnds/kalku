"""Which files depend on which, by what they import.

Line coverage cannot see a test that only *reads* what a module defines: a
constant, a class attribute. The test imports the module, so the module's own
code ran when the suite was collected and no line of it runs during the test,
and the test is credited with nothing of that module. A wekufe on such a
constant would then be judged against too few tests, and one that a test
would have caught survives.

What is certain is the dependency: a test that imports a module, directly or
through another, is affected by everything the module defines. That is read
from the syntax trees of the project's own files, which is a judgement made
here and not a guess. The one thing it cannot see is an import that is not
written as one, such as a name given to `importlib`.
"""

from __future__ import annotations

import ast
import os
from dataclasses import dataclass, field
from pathlib import Path

from . import project


@dataclass
class Graph:
    # Project file (relative, with `/`) to the project files it imports.
    imports: dict[str, set[str]] = field(default_factory=dict)
    _cache: dict[str, set[str]] | None = field(default=None, repr=False, compare=False)

    def importers_of(self, target: str) -> set[str]:
        """Every file that imports `target`, directly or through others."""
        reverse = self._reverse()
        seen: set[str] = set()
        todo = [target]
        while todo:
            for file in reverse.get(todo.pop(), ()):
                if file not in seen:
                    seen.add(file)
                    todo.append(file)
        return seen

    def direct_importers_of(self, target: str) -> set[str]:
        """The files that import `target` themselves."""
        return set(self._reverse().get(target, ()))

    def _reverse(self) -> dict[str, set[str]]:
        if self._cache is None:
            reverse: dict[str, set[str]] = {}
            for file, targets in self.imports.items():
                for t in targets:
                    reverse.setdefault(t, set()).add(file)
            self._cache = reverse
        return self._cache


def build(work: Path) -> Graph:
    """The import graph of every Python file under `work`."""
    files = python_files(work)
    present = set(files)
    roots = [Path(r).relative_to(work).as_posix() for r in project.import_roots(work)]
    roots = ["" if r == "." else r for r in roots]
    graph = Graph()
    for relative in files:
        graph.imports[relative] = _imports_of(work, relative, present, roots)
    return graph


def python_files(work: Path) -> list[str]:
    found = []
    for directory, names, files in os.walk(work):
        names[:] = [n for n in names if not project.is_ignored(n)]
        for name in files:
            if name.endswith(".py"):
                found.append((Path(directory) / name).relative_to(work).as_posix())
    return sorted(found)


def _search_roots(relative: str, roots: list[str]) -> list[str]:
    """Where Python would look for a name imported by this file: the import
    roots, and the file's own directory and its parents, which is where pytest
    puts a test directory that is not a package."""
    out = list(roots)
    parts = relative.split("/")[:-1]
    for i in range(len(parts), -1, -1):
        directory = "/".join(parts[:i])
        if directory not in out:
            out.append(directory)
    return out


def _locate(dotted: str, search: list[str], present: set[str]) -> str | None:
    path = dotted.replace(".", "/")
    for root in search:
        base = f"{root}/" if root else ""
        for candidate in (f"{base}{path}.py", f"{base}{path}/__init__.py"):
            if candidate in present:
                return candidate
    return None


def _package_of(relative: str, present: set[str]) -> list[str]:
    """The dotted package a file belongs to, as a list of names: the
    directories above it that are packages, up to the first that is not."""
    parts = relative.split("/")[:-1]
    package: list[str] = []
    while parts and "/".join([*parts, "__init__.py"]) in present:
        package.insert(0, parts.pop())
    return package


def _imports_of(work: Path, relative: str, present: set[str], roots: list[str]) -> set[str]:
    try:
        tree = ast.parse((work / relative).read_text(encoding="utf-8"))
    except (OSError, SyntaxError, ValueError, UnicodeDecodeError):
        return set()
    search = _search_roots(relative, roots)
    package = _package_of(relative, present)
    found: set[str] = set()

    def add(dotted: str) -> None:
        # Importing `a.b.c` runs `a`, `a.b` and `a.b.c`.
        parts = dotted.split(".")
        for i in range(1, len(parts) + 1):
            target = _locate(".".join(parts[:i]), search, present)
            if target is not None and target != relative:
                found.add(target)

    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                add(alias.name)
        elif isinstance(node, ast.ImportFrom):
            base = _resolve(node, package)
            if base is None:
                continue
            if base:
                add(base)
            for alias in node.names:
                add(f"{base}.{alias.name}" if base else alias.name)
    return found


def _resolve(node: ast.ImportFrom, package: list[str]) -> str | None:
    """The absolute name a `from ... import` names, or None when a relative
    import reaches above the package it is in."""
    if node.level == 0:
        return node.module or ""
    keep = len(package) - (node.level - 1)
    if keep < 1:
        return None
    base = package[:keep]
    if node.module:
        base = [*base, *node.module.split(".")]
    return ".".join(base)
