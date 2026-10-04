"""Which tests reach which lines.

Lines are counted by `sys.monitoring`, which is part of the interpreter, one
test at a time: the record is cleared before each test and read after it. A
line that ran when a module was imported is credited to no test, because it
ran before any test started, and most of what a module is made of runs there:
its function headers, its decorators and defaults, its constants. Those are
credited to the tests that exercise what they belong to, which is a judgement
about Python and is made here, from the syntax tree, and not guessed:

- the header of a function defined at import (its decorators, its signature,
  its defaults) belongs to the tests that ran any line of its body;
- the lines of a class that are not methods (its header, its attributes)
  belong to the tests that ran any line of the class;
- the lines of a module that are not functions or classes (its constants, its
  top-level statements) belong to the tests that ran any line of the module.

A test that only reads a constant or a class attribute runs no line of the
module at all, so what is shared this way (the module's own lines and the
class-level ones) also goes to every test that depends on the module by what
it imports (`dependency.py`).
"""

from __future__ import annotations

import ast
import bisect
from collections import defaultdict
from collections.abc import Callable, Iterable
from dataclasses import dataclass

Lines = dict[str, set[int]]


@dataclass(frozen=True)
class Rule:
    """When any line in `first..last` ran, the `owned` lines count as run.

    `shared` says the owned lines are the module's or a class's own, which
    are not tied to the body of a function."""

    first: int
    last: int
    owned: frozenset[int]
    shared: bool = False


def rules_of(text: str | None) -> list[Rule]:
    """The rules of one file, from its syntax tree."""
    if text is None:
        return []
    try:
        tree = ast.parse(text)
    except (SyntaxError, ValueError):
        return []
    rules: list[Rule] = []

    def span(node) -> tuple[int, int]:
        first = min([node.lineno, *[d.lineno for d in node.decorator_list]])
        return first, node.end_lineno or node.lineno

    def definitions(body: list[ast.stmt]):
        for stmt in body:
            if isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                yield stmt
            elif isinstance(stmt, (ast.If, ast.Try, ast.With, ast.For, ast.While)):
                for block in _blocks(stmt):
                    yield from definitions(block)

    def visit(body: list[ast.stmt], owner: tuple[int, int] | None) -> set[int]:
        """The lines of `body` that are not a definition, and a rule per
        definition; `owner` is the class this body belongs to."""
        own: set[int] = set()
        defined = list(definitions(body))
        inside: set[int] = set()
        for node in defined:
            first, last = span(node)
            inside.update(range(first, last + 1))
            body_first = node.body[0].lineno
            header = frozenset(range(first, body_first))
            if isinstance(node, ast.ClassDef):
                class_own = visit(node.body, (first, last))
                rules.append(Rule(body_first, last, header | frozenset(class_own), shared=True))
            else:
                rules.append(Rule(body_first, last, header))
        for stmt in body:
            if stmt in defined:
                continue
            own.update(range(stmt.lineno, (stmt.end_lineno or stmt.lineno) + 1))
        # What a compound statement holds that is not a definition is the
        # container's own, and what a definition holds is its own.
        return own - inside

    module_own = visit(tree.body, None)
    rules.append(Rule(1, len(text.splitlines()), frozenset(module_own), shared=True))
    return rules


def _blocks(stmt: ast.stmt):
    for name in ("body", "orelse", "finalbody"):
        block = getattr(stmt, name, None)
        if block:
            yield block
    for handler in getattr(stmt, "handlers", []):
        yield handler.body


def expand(
    per_test: dict[str, Lines],
    read: Callable[[str], str | None],
    depends: Callable[[str], set[str]] | None = None,
    files: Iterable[str] = (),
    wanted: Callable[[str], set[int] | None] | None = None,
) -> dict[str, Lines]:
    """Credit what ran at import to the tests that run what it belongs to.

    `depends(file)` names the test files that depend on a project file, and
    `files` the project files to consider besides the ones a test ran; `wanted`
    narrows what is shared to the lines that have something to measure."""
    rules: dict[str, list[Rule]] = {}

    def rules_for(file: str) -> list[Rule]:
        if file not in rules:
            rules[file] = rules_of(read(file))
        return rules[file]

    out: dict[str, Lines] = {}
    for test, lines in per_test.items():
        grown: Lines = {}
        for file, numbers in lines.items():
            ran = sorted(numbers)
            added = set(numbers)
            for rule in rules_for(file):
                at = bisect.bisect_left(ran, rule.first)
                if at < len(ran) and ran[at] <= rule.last:
                    added |= rule.owned
            grown[file] = added
        out[test] = grown
    if depends is not None:
        everything = sorted({*files, *(f for lines in per_test.values() for f in lines)})
        for file in everything:
            dependents = depends(file)
            if not dependents:
                continue
            shared = {n for rule in rules_for(file) if rule.shared for n in rule.owned}
            narrowed = wanted(file) if wanted is not None else None
            if narrowed is not None:
                shared &= narrowed
            if not shared:
                continue
            for test, grown in out.items():
                if test.partition("::")[0] in dependents:
                    grown.setdefault(file, set()).update(shared)
    return out


def invert(per_test: dict[str, Lines]) -> list[dict]:
    """Per-test lines turned around: for each line, the tests that reached it,
    in the order the tests were given."""
    reached: dict[tuple[str, int], list[str]] = defaultdict(list)
    for test, lines in per_test.items():
        for file, numbers in lines.items():
            for number in sorted(numbers):
                reached[(file, number)].append(test)
    return [
        {"file": file, "line": line, "tests": tests}
        for (file, line), tests in sorted(reached.items())
    ]
