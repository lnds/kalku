import ast
import dataclasses
import types

import pytest

from kalku_python import sites, spell
from kalku_python.source import Source


def walker(text):
    src = Source(text)
    return sites._Walker(src, sites._tokens(text, src), frozenset(spell.ALL), ())


def found(text, name):
    return [
        (s.original, s.start.line, s.start.byte) for s in sites.find("a.py", text, (name,)).sites
    ]


@pytest.mark.parametrize(
    ("prefix", "line"),
    [
        ("\x0c\n", 3),
        ("# a\u2028note\n", 3),
        ("x = '\x85'\n", 3),
        ("x = '\u2029'\n", 3),
    ],
)
def test_a_line_separator_the_tokenizer_ignores_does_not_move_an_operator(prefix, line):
    text = prefix + "def f(a, b):\n    return a > b\n"
    byte = len(text.encode("utf-8")) - len(" b\n")
    assert found(text, "compare") == [(">", line, byte - 1)]


def test_a_bare_carriage_return_counts_as_a_line_end():
    text = "def f(a, b):\r    return a > b\r"
    assert found(text, "compare") == [(">", 2, 26)]


def test_a_site_and_a_token_cannot_be_changed_once_made():
    site = sites.find("a.py", "x = 1\n", ("literal",)).sites[0]
    with pytest.raises(dataclasses.FrozenInstanceError):
        site.replacement = "9"
    with pytest.raises(dataclasses.FrozenInstanceError):
        sites._Token(1, "x", 0, 1).text = "y"


def test_a_candidate_is_not_risky_unless_it_says_so():
    assert sites._Candidate("literal", 0, 1, "2", None).risky is False
    w = walker("x = 1\n")
    w.propose("literal", 4, 5, "2")
    assert w.found[0].risky is False


@pytest.mark.parametrize("name", ["cast", "TypeVar", "ParamSpec", "TypeVarTuple", "NewType"])
def test_the_first_argument_that_names_a_type_is_not_a_value_but_the_second_is(name):
    text = f'{name}("Name", "second")\n'
    got = [s.original for s in sites.find("a.py", text, ("literal",)).sites]
    assert got == ['"second"']


def test_a_call_that_is_not_one_of_those_keeps_its_first_string():
    text = 'other("first", "second")\n'
    assert [s.original for s in sites.find("a.py", text, ("literal",)).sites] == [
        '"first"',
        '"second"',
    ]


def at(line, col, end_line, end_col):
    return types.SimpleNamespace(
        lineno=line, col_offset=col, end_lineno=end_line, end_col_offset=end_col
    )


def test_a_node_without_an_end_has_no_span():
    assert walker("x = 1\n").span(types.SimpleNamespace(lineno=1, col_offset=0)) is None


def test_a_node_whose_start_or_end_is_outside_the_file_has_no_span():
    w = walker("x = 1\n")
    assert w.span(at(9, 0, 1, 1)) is None
    assert w.span(at(1, 0, 9, 0)) is None


def test_a_node_that_ends_before_it_starts_has_no_span_and_an_empty_one_has():
    w = walker("x = 1\n")
    assert w.span(at(1, 4, 1, 2)) is None
    assert w.span(at(1, 3, 1, 3)) == (3, 3)


def without(monkeypatch, method, nodes):
    """`method` ("span" or "text") answering None for the nodes `nodes` accepts."""
    real = getattr(sites._Walker, method)
    monkeypatch.setattr(
        sites._Walker, method, lambda self, node: None if nodes(node) else real(self, node)
    )


def named(*ids):
    return lambda node: getattr(node, "id", None) in ids


def sites_of(text, name):
    return [s.original for s in sites.find("a.py", text, (name,)).sites]


@pytest.mark.parametrize("missing", ["a", "b"])
@pytest.mark.parametrize("method", ["span", "text"])
def test_an_operator_between_operands_that_cannot_be_placed_is_not_proposed(
    monkeypatch, missing, method
):
    without(monkeypatch, method, named(missing))
    if method == "text":
        # Operands are only placed, never read, so reading cannot fail them.
        assert sites_of("a > b\n", "compare") == [">"]
        assert sites_of("a and b\n", "connect") == ["and"]
    else:
        assert sites_of("a > b\n", "compare") == []
        assert sites_of("a and b\n", "connect") == []


@pytest.mark.parametrize(
    ("text", "spell_name"),
    [
        ("if x:\n    pass\n", "negate"),
        ("y = not x\n", "negate"),
        ("f(x)\n", "call"),
        ("y = 1\n", "literal"),
    ],
)
def test_nothing_is_proposed_for_text_that_cannot_be_read_back(monkeypatch, text, spell_name):
    without(monkeypatch, "text", lambda node: True)
    assert sites_of(text, spell_name) == []


@pytest.mark.parametrize("unreadable", ["operand", "whole"])
def test_a_not_needs_both_its_operand_and_itself_to_be_readable(monkeypatch, unreadable):
    def nodes(node):
        is_whole = isinstance(node, ast.UnaryOp)
        return is_whole if unreadable == "whole" else not is_whole and hasattr(node, "id")

    without(monkeypatch, "text", nodes)
    assert sites_of("y = not x\n", "negate") == []


def test_a_call_that_is_not_placed_is_not_proposed_to_be_dropped(monkeypatch):
    without(monkeypatch, "span", lambda node: node.__class__.__name__ == "Expr")
    assert sites_of("f(x)\n", "call") == []


@pytest.mark.parametrize("missing", ["pattern", "body"])
def test_a_case_that_cannot_be_placed_is_not_proposed_for_deletion(monkeypatch, missing):
    kinds = (ast.MatchValue,) if missing == "pattern" else (ast.Assign,)
    without(monkeypatch, "span", lambda node: isinstance(node, kinds))
    text = "match x:\n    case 1:\n        y = 0\n    case 2:\n        y = 1\n"
    assert sites_of(text, "arm") == []


@pytest.mark.parametrize("pattern", ["1", "(1)", "(((1)))", "((1 | 2))"])
def test_a_case_is_found_whatever_parentheses_surround_its_pattern(pattern):
    text = f"match x:\n    case {pattern}:\n        y = 0\n    case 9:\n        y = 1\n"
    assert sites_of(text, "arm")[0] == f"case {pattern}:\n        y = 0"


@pytest.mark.parametrize("call", ["cast()", "TypeVar()", "cast(x, 'a')", "cast(1, 'a')"])
def test_a_call_that_does_not_name_a_type_in_its_first_argument_is_walked_whole(call):
    got = sites_of(call + "\n", "literal")
    assert (
        got
        == {"cast()": [], "TypeVar()": [], "cast(x, 'a')": ["'a'"], "cast(1, 'a')": ["1", "'a'"]}[
            call
        ]
    )
