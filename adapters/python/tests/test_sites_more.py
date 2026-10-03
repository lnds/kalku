import ast

import pytest

from kalku_python import sites, spell
from kalku_python.sites import _dotted, _matches, _Walker
from kalku_python.source import Source


def of(spell_name, text, exclude=()):
    result = sites.find("a.py", text, (spell_name,), exclude)
    return [(s.original, s.replacement) for s in result.sites]


def all_of(text, exclude=()):
    return sites.find("a.py", text, spell.CAST, exclude).sites


# ---- every kind of statement is walked ----------------------------------------


def test_the_body_and_the_else_of_a_while_are_walked():
    text = "while a:\n    x = 1\nelse:\n    y = 2\n"
    assert of("literal", text) == [("1", "2"), ("2", "3")]


def test_the_body_the_else_and_the_condition_of_an_if_are_walked():
    text = "if a > 1:\n    x = 2\nelse:\n    y = 3\n"
    assert of("literal", text) == [("1", "2"), ("2", "3"), ("3", "4")]
    assert of("compare", text) == [(">", ">=")]


def test_for_try_with_and_their_clauses_are_walked():
    text = (
        "for i in range(3):\n    a = 4\nelse:\n    b = 5\n"
        "try:\n    c = 6\nexcept E:\n    d = 7\nelse:\n    e = 8\nfinally:\n    f = 9\n"
        "with ctx(10) as h:\n    g = 11\n"
    )
    assert [o for o, _ in of("literal", text)] == [str(n) for n in range(3, 12)]


def test_the_subject_of_a_match_is_walked_and_so_are_the_bodies():
    text = "match f(a > 1):\n    case 1:\n        x = 2\n    case _:\n        y = 3\n"
    assert of("compare", text) == [(">", ">=")]
    assert of("call", "match g():\n    case _:\n        h()\n") == [("h()", "pass")]


def test_a_decorated_async_method_belongs_to_its_class():
    text = "class C:\n    @deco(2)\n    async def m(self, a=3):\n        return a > 4\n"
    result = all_of(text)
    assert {s.enclosing for s in result if s.spell == "compare"} == {"C.m"}
    assert [s.original for s in result if s.spell == "literal"] == ["2", "3", "4"]
    # The decorator and the default belong to where the method is defined.
    assert {s.enclosing for s in result if s.original in ("2", "3")} == {"C"}


def test_a_class_decorator_and_a_nested_class_are_walked():
    text = "@register(1)\nclass A:\n    class B:\n        x = 2\n"
    result = all_of(text)
    assert [(s.original, s.enclosing) for s in result] == [("1", None), ("2", "A.B")]


def test_an_annotated_assignment_walks_its_value_and_not_its_annotation():
    text = "x: 'list[int]' = [1]\ny: int\n"
    assert of("literal", text) == [("1", "2")]


# ---- what is not proposed -----------------------------------------------------


def test_module_dunders_are_metadata_and_other_assignments_are_not():
    assert of("literal", '__all__ = ["a"]\n__version__ = "1"\n__all__ = x = ["b"]\n') == [
        ('"b"', '""')
    ]
    assert of("literal", 'self.__x = "a"\n__a__, b = 1, 2\n') == [
        ('"a"', '""'),
        ("1", "2"),
        ("2", "3"),
    ]
    assert of("literal", '__a__ = __b__ = "x"\nclass_ = "y"\n') == [('"y"', '""')]


def test_a_name_that_only_ends_in_dunder_is_not_one():
    assert of("literal", 'x__ = "a"\n__x = "b"\n') == [('"a"', '""'), ('"b"', '""')]


def test_type_checking_in_every_spelling_hides_its_body_and_keeps_its_else():
    for test in ("TYPE_CHECKING", "typing.TYPE_CHECKING", "t.TYPE_CHECKING"):
        text = f"if {test}:\n    x = 1\nelse:\n    y = 2\n"
        assert of("literal", text) == [("2", "3")], test
    assert of("literal", "if OTHER:\n    x = 1\n") == [("1", "2")]
    assert of("literal", "if a.OTHER:\n    x = 1\n") == [("1", "2")]
    assert of("literal", "if not TYPE_CHECKING:\n    x = 1\n") == [("1", "2")]


def test_a_main_guard_is_skipped_whole_and_a_look_alike_is_not():
    assert of("literal", 'if __name__ == "__main__":\n    x = 1\nelse:\n    y = 2\n') == []
    assert of("literal", 'if __name__ == "other":\n    x = 1\n') == [
        ('"other"', '""'),
        ("1", "2"),
    ]
    assert of("literal", 'if name == "__main__":\n    x = 1\n') == [
        ('"__main__"', '""'),
        ("1", "2"),
    ]
    assert of("literal", 'if __name__ != "__main__":\n    x = 1\n') != []
    assert of("literal", "if __name__ == 5:\n    x = 1\n") != []
    assert of("literal", 'if __name__ in ("__main__",):\n    x = 1\n') != []
    assert of("literal", 'if __name__ == "__main__" == x:\n    y = 1\n') != []


def test_a_string_that_is_a_whole_statement_is_a_comment_and_never_a_value():
    text = '"""m"""\n"second"\ndef f():\n    "doc"\n    "not doc"\nclass C:\n    "doc"\n    "x"\n'
    assert of("literal", text) == []


def test_a_string_statement_that_is_not_a_docstring_is_still_a_value():
    # An expression statement is a docstring when it is a string; the call
    # spell is for calls.
    assert of("call", '"a"\nf()\n') == [("f()", "pass")]


# ---- operators between operands -----------------------------------------------


def test_an_operator_between_parenthesised_operands_is_found():
    assert of("compare", "x = (a)>=(b)\n") == [(">=", ">")]
    assert of("connect", "x = (a)and(b)\n") == [("and", "or")]
    assert of("compare", "x = ((a)) < ((b))\n") == [("<", "<=")]


def test_a_boolean_chain_has_a_site_per_operator_and_each_is_its_own_word():
    text = "x = a and b and c\ny = a or b or c\n"
    assert of("connect", text) == [("and", "or"), ("and", "or"), ("or", "and"), ("or", "and")]


def test_a_comparison_whose_operator_is_hidden_by_a_comment_is_found():
    assert of("compare", "x = (a  # c\n     == b)\n") == [("==", "!=")]


def test_not_of_every_kind_of_operand_is_parenthesised_only_when_it_has_to():
    cases = {
        "not a": "a",
        "not a.b": "a.b",
        "not f(a)": "f(a)",
        "not a[0]": "a[0]",
        "not [1]": "[1]",
        "not {1: 2}": "{1: 2}",
        "not {1}": "{1}",
        "not (x for x in y)": "(x for x in y)",
        "not [x for x in y]": "[x for x in y]",
        "not {x for x in y}": "{x for x in y}",
        "not {x: 1 for x in y}": "{x: 1 for x in y}",
        "not (a and b)": "(a and b)",
        "not (a < b)": "(a < b)",
        "not (a if b else c)": "(a if b else c)",
        "not (lambda: 1)": "(lambda: 1)",
        "not (await_ + 1)": "(await_ + 1)",
        'not f"x"': 'f"x"',
        'not "x"': '"x"',
    }
    for source, expected in cases.items():
        got = [r for o, r in of("negate", f"x = {source}\n") if o == source]
        assert got == [expected], source


def test_a_condition_gets_its_own_parentheses_unless_it_is_one_word():
    got = dict(of("negate", "if a < b:\n    pass\nif c.d:\n    pass\nif e(f):\n    pass\n"))
    assert got == {"a < b": "not (a < b)", "c.d": "not c.d", "e(f)": "not e(f)"}


def test_a_condition_that_is_a_constant_or_a_negation_is_left_alone_and_a_not_in_is_not():
    assert of("negate", "if 1:\n    pass\nif None:\n    pass\n") == []
    assert of("negate", "if not a:\n    pass\n") == [("not a", "a")]
    assert of("negate", "if a not in b:\n    pass\n") == [("a not in b", "not (a not in b)")]
    assert of("negate", "if -a:\n    pass\n") == [("-a", "not (-a)")]


# ---- calls ---------------------------------------------------------------------


def test_exclude_calls_matches_dotted_names_exactly_or_by_prefix():
    text = "a.b.c()\nab()\nlogging.info(1)\nlogging2.info(2)\n"
    assert of("call", text, ["a.b.c"]) == [
        ("ab()", "pass"),
        ("logging.info(1)", "pass"),
        ("logging2.info(2)", "pass"),
    ]
    assert of("call", text, ["a.*"]) == [
        ("ab()", "pass"),
        ("logging.info(1)", "pass"),
        ("logging2.info(2)", "pass"),
    ]
    assert of("call", text, ["logging.*"]) == [
        ("a.b.c()", "pass"),
        ("ab()", "pass"),
        ("logging2.info(2)", "pass"),
    ]
    assert of("call", text, ["*"]) == []
    assert of("call", text, ["log*ing"]) == [
        (o, "pass") for o in ("a.b.c()", "ab()", "logging.info(1)", "logging2.info(2)")
    ]


def test_a_call_that_is_not_by_name_has_no_name_to_exclude():
    # Neither has a dotted name, so no pattern can exclude it, not even `*`.
    assert of("call", "f()()\n(a or b).c()\n", ["f", "*"]) == [
        ("f()()", "pass"),
        ("(a or b).c()", "pass"),
    ]
    assert _dotted(ast.parse("a.b.c").body[0].value) == "a.b.c"
    assert _dotted(ast.parse("f().g").body[0].value) == ""
    assert _dotted(ast.parse("x").body[0].value) == "x"
    assert _dotted(ast.parse("1").body[0].value) == ""


@pytest.mark.parametrize(
    ("name", "pattern", "wanted"),
    [
        ("a.b", "a.b", True),
        ("a.b", "a.c", False),
        ("a.b", "a.*", True),
        ("a.", "a.*", True),
        ("b.a", "a.*", False),
        ("a.b", "*", True),
        ("a.b", "a*b", False),
        ("a.b", "*b", False),
        ("a", "", False),
    ],
)
def test_exclude_patterns(name, pattern, wanted):
    assert _matches(name, pattern) is wanted


def test_an_awaited_call_is_dropped_whole_and_an_awaited_value_is_not():
    assert of("call", "async def f():\n    await a.b(1)\n    await c\n    d = await e()\n") == [
        ("await a.b(1)", "pass")
    ]
    assert of("call", "async def f():\n    await log.info(1)\n", ["log.*"]) == []


def test_a_call_in_every_position_but_a_statement_is_a_value():
    # A list of a call is not a call statement; a parenthesised one is.
    assert of("call", "x = f()\nreturn_ = g(h())\n[i()]\n(j())\nk.l()\n") == [
        ("(j())", "pass"),
        ("k.l()", "pass"),
    ]


# ---- match --------------------------------------------------------------------


def test_a_case_with_a_guard_and_a_body_of_several_statements_is_deleted_whole():
    text = "match x:\n    case 1 if y:\n        a = 1\n        b = 2\n    case _:\n        pass\n"
    assert [o for o, _ in of("arm", text)][0] == "case 1 if y:\n        a = 1\n        b = 2"


def test_deleting_the_cases_of_a_match_one_at_a_time_keeps_the_file_parseable():
    text = "match x:\n    case 1:\n        pass\n    case 2:\n        pass\n"
    src = Source(text)
    for s in sites.find("a.py", text, ("arm",), ()).sites:
        ast.parse(src.splice(s.start.byte, s.end.byte, s.replacement))
    assert len(of("arm", text)) == 2


def test_a_match_inside_a_function_and_inside_a_case_has_arms_in_order():
    text = (
        "def f(x):\n    match x:\n        case 1:\n            match y:\n"
        "                case 2:\n                    pass\n                case _:\n                    pass\n"
        "        case _:\n            pass\n"
    )
    result = sites.find("a.py", text, ("arm",), ()).sites
    assert [s.start.line for s in result] == [3, 5, 7, 9]


# ---- positions ----------------------------------------------------------------


def test_a_site_in_a_file_with_tabs_and_unicode_names_is_exact():
    text = "def f(é):\n\treturn é >= 1\n"
    data = text.encode()
    for s in all_of(text):
        assert data[s.start.byte : s.end.byte].decode() == s.original


def test_the_last_line_without_a_newline_is_searched():
    assert of("compare", "x = a > b") == [(">", ">=")]


def test_an_empty_file_has_no_sites():
    assert all_of("").__len__() == 0
    assert all_of("# only a comment\n") == []


def test_a_file_whose_tokens_cannot_be_read_still_has_its_syntax_tree_sites():
    walker = _Walker(Source("x"), [], frozenset(spell.CAST), ())
    assert walker.tokens_between(0, 1) == []
    assert walker.span(ast.parse("x").body[0]) == (0, 1)
    assert walker.span(ast.arguments()) is None


def test_the_tokens_between_two_operands_leave_out_their_parentheses():
    text = "x = (a) >= (b)\n"
    found = sites.find("a.py", text, ("compare",), ())
    assert [s.original for s in found.sites] == [">="]
    walker_tokens = sites._tokens(text, Source(text))
    walker = _Walker(Source(text), walker_tokens, frozenset(), ())
    between = walker.tokens_between(5, 13)
    assert [t.text for t in between] == ["a", ">=", "b"]


def test_a_malformed_token_stream_costs_the_tokens_and_not_the_search():
    # An unterminated triple-quoted string cannot parse, so it is a ParseError.
    with pytest.raises(sites.ParseError):
        all_of('x = """abc\n')


def test_a_candidate_whose_text_moved_is_dropped_not_cast():
    src = Source("a >= b\n")
    stale = sites._Candidate("compare", 2, 4, ">", None, expected="<=")
    assert sites._splices(src, stale) is False
    assert sites._splices(src, sites._Candidate("compare", 2, 4, ">", None, expected=">=")) is True
    assert sites._splices(src, sites._Candidate("compare", 2, 4, ">=", None)) is False
    assert sites._splices(src, sites._Candidate("compare", 90, 99, ">", None)) is False


def test_a_risky_candidate_is_parsed_and_a_safe_one_is_not():
    src = Source("if a:\n    pass\n")
    broken = sites._Candidate("arm", 0, 5, "(", None, risky=True)
    assert sites._splices(src, broken) is False
    assert sites._splices(src, sites._Candidate("arm", 0, 5, "(", None, risky=False)) is True
    assert sites._splices(src, sites._Candidate("arm", 0, 2, "", None, risky=True)) is False
    assert sites._splices(src, sites._Candidate("arm", 3, 4, "b", None, risky=True)) is True


def test_a_ordinal_counts_per_scope_spell_and_original_text():
    text = "def f(a):\n    return a > 1 or a > 1\n\ndef g(a):\n    return a > 1\n"
    numbered = [(s.enclosing, s.spell, s.original, s.ordinal) for s in all_of(text)]
    assert ("f", "compare", ">", 1) in numbered and ("f", "compare", ">", 2) in numbered
    assert ("g", "compare", ">", 1) in numbered
    assert ("f", "literal", "1", 1) in numbered and ("f", "literal", "1", 2) in numbered


def test_every_site_id_is_twelve_hex_digits_and_unique_in_a_file():
    result = all_of("def f(a, b):\n    return a > 1 and b < 2 or not a\n")
    ids = [s.site_id for s in result]
    assert all(len(i) == 12 and set(i) <= set("0123456789abcdef") for i in ids)
    assert len(ids) == len(set(ids))


def test_a_site_id_depends_on_the_file_and_the_place_and_the_replacement():
    a = all_of("x = a > 1\n")
    b = all_of("y = a > 1\n")
    assert {s.site_id for s in a}.isdisjoint({s.site_id for s in b})
    assert [s.site_id for s in a] == [s.site_id for s in all_of("x = a > 1\n")]
