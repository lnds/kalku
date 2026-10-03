"""Where a defect can be cast in one Python file.

The AST says what and where; the tokens say where an operator sits between
two operands, which `ast` does not keep. A candidate whose text cannot be
pinned down exactly yields no site rather than a guess, and a candidate that
can break the syntax is parsed before anyone is asked to cast it.

What is *not* proposed matters as much as what is. The parser is Python's
own, so this never scans text: a literal inside a comment or a docstring is
not a node and cannot become a site.

Not proposed, on purpose:
- docstrings and module dunders (`__all__`, `__version__`): documentation and
  metadata, not behaviour;
- annotations, the type named by `cast(...)` or `TypeVar(...)`,
  `if TYPE_CHECKING:` and `if __name__ == "__main__":`: nothing a test can
  run, or a different program;
- patterns of `match`, whose literals are structure and not values;
- the arguments of a call that `exclude_calls` names;
- test files, which the service refuses before it gets here: mutating the
  oracle is not a measurement of the oracle.
"""

from __future__ import annotations

import ast
import bisect
import hashlib
import io
import tokenize
from collections.abc import Iterable
from dataclasses import dataclass

from .source import Position, Source


@dataclass(frozen=True)
class Site:
    site_id: str
    file: str
    enclosing: str | None
    ordinal: int
    start: Position
    end: Position
    spell: str
    original: str
    replacement: str


@dataclass
class Found:
    sites: list[Site]
    # Candidates thrown away because their wekufe would not parse.
    dropped: int


class ParseError(Exception):
    """A file that cannot be searched."""


@dataclass
class _Candidate:
    spell: str
    start: int
    end: int
    replacement: str
    enclosing: str | None
    # What the source must say at `start:end`, when the walk knows it.
    expected: str | None = None
    # Whether the replacement can leave a file that does not parse.
    risky: bool = False


_COMPARE = {
    ">=": ">",
    ">": ">=",
    "<=": "<",
    "<": "<=",
    "==": "!=",
    "!=": "==",
    "is": "is not",
    "is not": "is",
    "in": "not in",
    "not in": "in",
}

_OP_OF = {
    ast.Gt: ">",
    ast.GtE: ">=",
    ast.Lt: "<",
    ast.LtE: "<=",
    ast.Eq: "==",
    ast.NotEq: "!=",
    ast.Is: "is",
    ast.IsNot: "is not",
    ast.In: "in",
    ast.NotIn: "not in",
}

# What can be put after `not` without a pair of parentheses.
_ATOMIC = (
    ast.Name,
    ast.Attribute,
    ast.Call,
    ast.Subscript,
    ast.Constant,
    ast.List,
    ast.Dict,
    ast.Set,
    ast.ListComp,
    ast.SetComp,
    ast.DictComp,
    ast.GeneratorExp,
    ast.JoinedStr,
)

# Calls whose first argument is the name of a type or of a type variable, and
# the position of that argument.
_TYPE_ARGUMENT = {"cast": 0, "TypeVar": 0, "ParamSpec": 0, "TypeVarTuple": 0, "NewType": 0}

_SIGNIFICANT_SKIPPED = {
    tokenize.COMMENT,
    tokenize.NL,
    tokenize.NEWLINE,
    tokenize.INDENT,
    tokenize.DEDENT,
}


def find(file: str, text: str, spells: Iterable[str], exclude_calls: Iterable[str] = ()) -> Found:
    """Search one file."""
    try:
        tree = ast.parse(text)
    except SyntaxError as e:
        raise ParseError(f"line {e.lineno}: {e.msg}") from None
    except ValueError as e:  # a null byte, say
        raise ParseError(str(e)) from None
    src = Source(text)
    tokens = _tokens(text, src)
    walker = _Walker(src, tokens, frozenset(spells), tuple(exclude_calls))
    walker.visit_body(tree.body)

    resolved = sorted(
        {(c.start, c.end, c.spell, c.replacement): c for c in walker.found}.values(),
        key=lambda c: (c.start, c.end, c.spell, c.replacement),
    )
    kept: list[_Candidate] = []
    dropped = 0
    for c in resolved:
        if _splices(src, c):
            kept.append(c)
        else:
            dropped += 1
    file_hash = hashlib.sha256(src.data).hexdigest()
    return Found(sites=_number(file, file_hash, src, kept), dropped=dropped)


def _splices(src: Source, c: _Candidate) -> bool:
    """The wekufe has to differ from the original, and still be a Python file."""
    original = src.slice(c.start, c.end)
    if original is None or original == c.replacement:
        return False
    if c.expected is not None and original != c.expected:
        return False
    if not c.risky:
        return True
    try:
        ast.parse(src.splice(c.start, c.end, c.replacement))
    except (SyntaxError, ValueError):
        return False
    return True


def _number(file: str, file_hash: str, src: Source, kept: list[_Candidate]) -> list[Site]:
    """Ordinal: the 1-based occurrence of `(enclosing, spell, original)` in
    source order. It is what lets a declared equivalent survive an edit
    elsewhere in the file, where a line number would not."""
    seen: dict[tuple[str | None, str, str], int] = {}
    sites = []
    for c in kept:
        original = src.slice(c.start, c.end) or ""
        key = (c.enclosing, c.spell, original)
        seen[key] = seen.get(key, 0) + 1
        digest = hashlib.sha256(
            f"{file_hash}|{c.start}|{c.end}|{c.spell}|{c.replacement}".encode()
        ).hexdigest()
        sites.append(
            Site(
                site_id=digest[:12],
                file=file,
                enclosing=c.enclosing,
                ordinal=seen[key],
                start=src.position(c.start),
                end=src.position(c.end),
                spell=c.spell,
                original=original,
                replacement=c.replacement,
            )
        )
    return sites


@dataclass(frozen=True)
class _Token:
    kind: int
    text: str
    start: int
    end: int


def _tokens(text: str, src: Source) -> list[_Token]:
    """The significant tokens of the file, positioned in bytes."""
    lines = text.splitlines(keepends=True)
    starts = [0]
    for line in lines:
        starts.append(starts[-1] + len(line.encode("utf-8")))

    def byte(pos: tuple[int, int]) -> int:
        row, col = pos
        if row - 1 >= len(lines):
            return len(src.data)
        return starts[row - 1] + len(lines[row - 1][:col].encode("utf-8"))

    out = []
    try:
        for tok in tokenize.generate_tokens(io.StringIO(text).readline):
            if tok.type in _SIGNIFICANT_SKIPPED or tok.type == tokenize.ENDMARKER:
                continue
            out.append(_Token(tok.type, tok.string, byte(tok.start), byte(tok.end)))
    except (tokenize.TokenError, IndentationError):
        pass
    return out


def _matches(name: str, pattern: str) -> bool:
    """`exclude_calls` patterns: an exact name, or a prefix ending in `*`."""
    head, star, tail = pattern.partition("*")
    if not star:
        return name == pattern
    return tail == "" and name.startswith(head)


def _dotted(node: ast.expr) -> str:
    """`logging.info` for the function of a call, or an empty name."""
    parts = []
    while isinstance(node, ast.Attribute):
        parts.append(node.attr)
        node = node.value
    if isinstance(node, ast.Name):
        parts.append(node.id)
        return ".".join(reversed(parts))
    return ""


def _is_docstring(stmt: ast.stmt) -> bool:
    return (
        isinstance(stmt, ast.Expr)
        and isinstance(stmt.value, ast.Constant)
        and isinstance(stmt.value.value, str)
    )


def _is_type_checking(test: ast.expr) -> bool:
    return (isinstance(test, ast.Name) and test.id == "TYPE_CHECKING") or (
        isinstance(test, ast.Attribute) and test.attr == "TYPE_CHECKING"
    )


def _is_main_guard(test: ast.expr) -> bool:
    return (
        isinstance(test, ast.Compare)
        and isinstance(test.left, ast.Name)
        and test.left.id == "__name__"
        and len(test.ops) == 1
        and isinstance(test.ops[0], ast.Eq)
        and isinstance(test.comparators[0], ast.Constant)
        and test.comparators[0].value == "__main__"
    )


class _Walker(ast.NodeVisitor):
    def __init__(
        self, src: Source, tokens: list[_Token], spells: frozenset[str], exclude: tuple[str, ...]
    ):
        self.src = src
        self.tokens = tokens
        self._starts = [t.start for t in tokens]
        self.spells = spells
        self.exclude = exclude
        self.scope: list[str] = []
        self.found: list[_Candidate] = []

    # ---- helpers -----------------------------------------------------------

    def span(self, node: ast.AST) -> tuple[int, int] | None:
        end_line = getattr(node, "end_lineno", None)
        if end_line is None:
            return None
        start = self.src.byte_at(node.lineno, node.col_offset)
        end = self.src.byte_at(end_line, node.end_col_offset)
        if start is None or end is None or start > end:
            return None
        return start, end

    def text(self, node: ast.AST) -> str | None:
        span = self.span(node)
        return self.src.slice(*span) if span else None

    def enclosing(self) -> str | None:
        return ".".join(self.scope) if self.scope else None

    def propose(
        self,
        spell: str,
        start: int,
        end: int,
        replacement: str,
        expected: str | None = None,
        risky: bool = False,
    ) -> None:
        if spell in self.spells:
            self.found.append(
                _Candidate(spell, start, end, replacement, self.enclosing(), expected, risky)
            )

    def tokens_between(self, start: int, end: int) -> list[_Token]:
        """The tokens inside `start:end`, without the parentheses around operands."""
        lo = bisect.bisect_left(self._starts, start)
        out = []
        for tok in self.tokens[lo:]:
            if tok.start >= end:
                break
            if tok.end <= end and tok.text not in ("(", ")"):
                out.append(tok)
        return out

    def excluded(self, call: ast.Call) -> bool:
        name = _dotted(call.func)
        return bool(name) and any(_matches(name, p) for p in self.exclude)

    # ---- structure ---------------------------------------------------------

    def visit_body(self, body: list[ast.stmt]) -> None:
        for stmt in body:
            if _is_docstring(stmt):
                continue
            self.visit(stmt)

    def _function(self, node: ast.FunctionDef | ast.AsyncFunctionDef) -> None:
        for decorator in node.decorator_list:
            self.visit(decorator)
        args = node.args
        for default in [*args.defaults, *[d for d in args.kw_defaults if d is not None]]:
            self.visit(default)
        self.scope.append(node.name)
        self.visit_body(node.body)
        self.scope.pop()

    visit_FunctionDef = _function
    visit_AsyncFunctionDef = _function

    def visit_ClassDef(self, node: ast.ClassDef) -> None:
        for decorator in node.decorator_list:
            self.visit(decorator)
        self.scope.append(node.name)
        self.visit_body(node.body)
        self.scope.pop()

    def visit_Lambda(self, node: ast.Lambda) -> None:
        self.visit(node.body)

    def visit_AnnAssign(self, node: ast.AnnAssign) -> None:
        # The annotation is not a value; the assigned expression is.
        if node.value is not None:
            self.visit(node.value)

    def visit_Assign(self, node: ast.Assign) -> None:
        # `__all__`, `__version__`: what a module says about itself, not what it does.
        if all(
            isinstance(t, ast.Name) and t.id.startswith("__") and t.id.endswith("__")
            for t in node.targets
        ):
            return
        self.generic_visit(node)

    def visit_match_case(self, node: ast.match_case) -> None:
        if node.guard is not None:
            self.visit(node.guard)
        self.visit_body(node.body)

    # ---- conditions --------------------------------------------------------

    def _condition(self, test: ast.expr) -> None:
        """`if c` becomes `if not (c)`."""
        if isinstance(test, (ast.Constant, ast.UnaryOp)) and (
            isinstance(test, ast.Constant) or isinstance(test.op, ast.Not)
        ):
            return
        span = self.span(test)
        original = self.text(test)
        if span is None or original is None:
            return
        wrapped = f"not {original}" if isinstance(test, _ATOMIC) else f"not ({original})"
        self.propose("negate", span[0], span[1], wrapped, expected=original)

    def visit_If(self, node: ast.If) -> None:
        if _is_main_guard(node.test):
            return
        if _is_type_checking(node.test):
            self.visit_body(node.orelse)
            return
        self._condition(node.test)
        self.visit(node.test)
        self.visit_body(node.body)
        self.visit_body(node.orelse)

    def visit_While(self, node: ast.While) -> None:
        self._condition(node.test)
        self.visit(node.test)
        self.visit_body(node.body)
        self.visit_body(node.orelse)

    def visit_IfExp(self, node: ast.IfExp) -> None:
        self._condition(node.test)
        self.generic_visit(node)

    # ---- expressions -------------------------------------------------------

    def visit_Compare(self, node: ast.Compare) -> None:
        operands = [node.left, *node.comparators]
        for i, op in enumerate(node.ops):
            wanted = _OP_OF.get(type(op))
            left, right = self.span(operands[i]), self.span(operands[i + 1])
            if wanted is None or left is None or right is None:
                continue
            between = self.tokens_between(left[1], right[0])
            words = wanted.split()
            if len(between) < len(words):
                continue
            # The operator is the last tokens before the right operand that
            # spell it; anything else between two operands is part of them.
            tail = between[-len(words) :]
            if [t.text for t in tail] != words:
                continue
            start, end = tail[0].start, tail[-1].end
            self.propose("compare", start, end, _COMPARE[wanted], expected=wanted)
        self.generic_visit(node)

    def visit_BoolOp(self, node: ast.BoolOp) -> None:
        word, other = ("and", "or") if isinstance(node.op, ast.And) else ("or", "and")
        for a, b in zip(node.values, node.values[1:], strict=False):
            left, right = self.span(a), self.span(b)
            if left is None or right is None:
                continue
            between = self.tokens_between(left[1], right[0])
            if between and between[-1].text == word:
                self.propose("connect", between[-1].start, between[-1].end, other, expected=word)
        self.generic_visit(node)

    def visit_UnaryOp(self, node: ast.UnaryOp) -> None:
        if isinstance(node.op, ast.Not):
            span = self.span(node)
            operand = self.text(node.operand)
            whole = self.text(node)
            if span is not None and operand is not None and whole is not None:
                kept = operand if isinstance(node.operand, _ATOMIC) else f"({operand})"
                self.propose("negate", span[0], span[1], kept, expected=whole)
        self.generic_visit(node)

    def visit_Constant(self, node: ast.Constant) -> None:
        value = node.value
        span = self.span(node)
        original = self.text(node)
        if span is None or original is None:
            return
        if isinstance(value, bool):
            self.propose("literal", *span, "False" if value else "True", expected=original)
        elif isinstance(value, int):
            # Plain decimal digits only: a hex literal or one with underscores
            # would be rewritten into another notation, which is not one
            # defect alone.
            if original.isdigit() and (len(original) == 1 or not original.startswith("0")):
                self.propose("literal", *span, str(value + 1), expected=original)
        elif isinstance(value, str) and value:
            self.propose("literal", *span, '""', expected=original)

    def visit_JoinedStr(self, node: ast.JoinedStr) -> None:
        # The pieces of text of an f-string are not values; the expressions
        # inside the braces are.
        for part in node.values:
            if isinstance(part, ast.FormattedValue):
                self.visit(part.value)

    def visit_Call(self, node: ast.Call) -> None:
        if self.excluded(node):
            return
        if _TYPE_ARGUMENT.get(_dotted(node.func).rsplit(".", 1)[-1], -1) == 0 and (
            node.args
            and isinstance(node.args[0], ast.Constant)
            and isinstance(node.args[0].value, str)
        ):
            # `cast("list[int]", x)`: the first argument names a type, which
            # no test can tell from another string.
            self.visit(node.func)
            for arg in node.args[1:]:
                self.visit(arg)
            for keyword in node.keywords:
                self.visit(keyword)
            return
        self.generic_visit(node)

    def visit_Expr(self, node: ast.Expr) -> None:
        value = node.value
        awaited = value.value if isinstance(value, ast.Await) else value
        if isinstance(awaited, ast.Call) and not self.excluded(awaited):
            span = self.span(node)
            original = self.text(node)
            if span is not None and original is not None:
                self.propose("call", *span, "pass", expected=original)
        self.generic_visit(node)

    # ---- match -------------------------------------------------------------

    def visit_Match(self, node: ast.Match) -> None:
        self.visit(node.subject)
        for case in node.cases:
            self._arm(case)
            self.visit_match_case(case)

    def _arm(self, case: ast.match_case) -> None:
        """Delete one `case`, text and body."""
        first = self.span(case.pattern)
        last = self.span(case.body[-1]) if case.body else None
        if first is None or last is None:
            return
        lo = bisect.bisect_left(self._starts, first[0])
        # The `case` keyword is the token before the pattern.
        for tok in reversed(self.tokens[max(0, lo - 3) : lo]):
            if tok.text == "case":
                self.propose("arm", tok.start, last[1], "", risky=True)
                return
