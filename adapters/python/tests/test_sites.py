import ast

import pytest

from kalku_python import sites, spell
from kalku_python.source import Source


def found(text, spells=spell.CAST, exclude=()):
    return sites.find("a.py", text, spells, exclude)


def of(spell_name, text, exclude=()):
    return [(s.original, s.replacement) for s in found(text, (spell_name,), exclude).sites]


def test_a_file_that_does_not_parse_is_an_error_naming_the_line():
    with pytest.raises(sites.ParseError) as caught:
        found("def f(:\n    pass\n")
    assert str(caught.value).startswith("line 1")


def test_a_null_byte_is_an_error_not_a_crash():
    with pytest.raises(sites.ParseError):
        found("x = 1\x00\n")


def test_a_site_says_where_it_is_in_lines_characters_and_bytes():
    # `é` is two bytes and one character: the counts part company here,
    # which is the whole reason the module exists.
    text = 's = "é"; a = 1 >= 0\n'
    site = next(s for s in found(text).sites if s.spell == "compare")
    data = text.encode()
    assert data[site.start.byte : site.end.byte] == b">="
    assert (site.start.line, site.start.col) == (1, 16)
    assert site.end.col == 18


def test_every_site_after_a_multibyte_line_is_still_exact():
    text = "x = 'ñandú'\ny = 'ü' if x > 1 else 2\n"
    data = text.encode()
    for s in found(text).sites:
        assert data[s.start.byte : s.end.byte].decode() == s.original


def test_crlf_files_are_read_the_same():
    text = "def f(a):\r\n    return a > 1\r\n"
    ordered = [(s.start.line, s.original) for s in found(text).sites]
    assert (2, ">") in ordered and (2, "1") in ordered


def test_a_literal_in_an_fstring_expression_is_a_value_and_its_text_is_not():
    assert of("literal", 'x = f"total {a + 1} of 5"\n') == [("1", "2")]


def test_only_plain_decimal_integers_are_succeeded():
    assert of("literal", "a = 9\nb = 0xA\nc = 1_0\nd = 0b1\ne = 00\nf = 1e3\n") == [("9", "10")]


def test_booleans_swap_and_empty_strings_are_left_alone():
    assert of("literal", 'a = True\nb = False\nc = ""\nd = "x"\n') == [
        ("True", "False"),
        ("False", "True"),
        ('"x"', '""'),
    ]


def test_a_name_that_only_looks_like_a_boolean_is_not_one():
    assert of("literal", "a = None\nb = TrueValue\n") == []


def test_an_operator_next_to_parentheses_and_comments_is_found():
    text = "x = (a  # first\n     >=\n     (b))\n"
    assert of("compare", text) == [(">=", ">")]


def test_is_not_and_not_in_are_one_operator_each():
    assert of("compare", "x = a is not b\ny = a not in c\n") == [("is not", "is"), ("not in", "in")]


def test_a_chained_comparison_has_a_site_per_operator():
    assert of("compare", "x = 0 <= a < 10\n") == [("<=", "<"), ("<", "<=")]


def test_not_of_a_compound_operand_keeps_its_parentheses():
    assert of("negate", "x = not (a or b)\n") == [("not (a or b)", "(a or b)")]
    assert of("negate", "x = not a.b\n") == [("not a.b", "a.b")]


def test_a_condition_that_is_already_negated_or_constant_is_not_negated_again():
    assert of("negate", "if not a:\n    pass\n") == [("not a", "a")]
    assert of("negate", "while True:\n    break\n") == []


def test_a_condition_of_every_kind_gets_a_negate_site():
    text = "if a:\n    pass\nelif b > 1:\n    pass\nwhile c:\n    pass\nx = 1 if d else 2\n"
    assert of("negate", text) == [
        ("a", "not a"),
        ("b > 1", "not (b > 1)"),
        ("c", "not c"),
        ("d", "not d"),
    ]


def test_a_statement_call_can_be_dropped_and_a_value_call_cannot():
    assert of("call", "f(1)\nx = g(2)\nh(3).k()\n") == [("f(1)", "pass"), ("h(3).k()", "pass")]


def test_an_awaited_statement_call_can_be_dropped():
    assert of("call", "async def f():\n    await g()\n    y = await h()\n") == [
        ("await g()", "pass")
    ]


def test_exclude_calls_skips_the_call_and_its_arguments():
    text = 'logging.info("a", b > 1)\nrun(1)\nself.log.debug(2)\n'
    assert of("call", text, ["logging.*", "self.log.*"]) == [("run(1)", "pass")]
    assert of("compare", text, ["logging.*"]) == []
    assert of("literal", text, ["logging.*", "self.log.*"]) == [("1", "2")]


def test_an_exact_exclude_pattern_matches_the_whole_name_only():
    assert of("call", "print(1)\nprinter(2)\n", ["print"]) == [("printer(2)", "pass")]


def test_a_case_is_deleted_whole_and_the_only_case_is_not_proposed():
    text = "match x:\n    case 1:\n        a = 1\n    case _:\n        a = 2\n"
    assert [o for o, _ in of("arm", text)] == ["case 1:\n        a = 1", "case _:\n        a = 2"]
    only = "match x:\n    case _:\n        a = 1\n"
    result = found(only, ("arm",))
    assert result.sites == [] and result.dropped == 1


def test_a_guard_and_its_condition_are_values_and_the_pattern_is_not():
    text = "match x:\n    case 1 if y > 2:\n        pass\n"
    assert of("literal", text) == [("2", "3")]
    assert of("compare", text) == [(">", ">=")]


def test_a_spell_that_was_not_asked_for_is_not_proposed():
    assert {s.spell for s in found("if a >= 1 and b:\n    pass\n", ("connect",)).sites} == {
        "connect"
    }


def test_defaults_and_decorators_are_values_and_annotations_are_not():
    text = "@deco(retries=3)\ndef f(a: 'int' = 1, *, b: list[int] = [2]) -> 'bool':\n    pass\n"
    assert [o for o, _ in of("literal", text)] == ["3", "1", "2"]


def test_a_lambda_and_a_comprehension_belong_to_their_function():
    text = "def f(xs):\n    return [x for x in xs if x > 1], (lambda y: y < 2)\n"
    result = found(text, ("compare",)).sites
    assert {s.enclosing for s in result} == {"f"}
    assert len(result) == 2


def test_a_type_checking_block_and_a_main_guard_are_skipped_but_else_is_not():
    text = "if TYPE_CHECKING:\n    x = 1\nelse:\n    y = 2\nif __name__ == '__main__':\n    z = 3\n"
    assert of("literal", text) == [("2", "3")]


def test_module_dunders_are_metadata_and_other_assignments_are_not():
    assert of("literal", '__version__ = "1"\n__all__ = ["a"]\nVERSION = "1"\n') == [('"1"', '""')]


def test_ordinals_count_the_same_text_in_the_same_scope():
    text = "def f(a):\n    return a > 1, a > 2\n\ndef g(a):\n    return a > 1\n"
    got = [(s.enclosing, s.original, s.ordinal) for s in found(text, ("compare",)).sites]
    assert got == [("f", ">", 1), ("f", ">", 2), ("g", ">", 1)]


def test_an_edit_elsewhere_keeps_the_semantic_key_of_a_site():
    before = found("def f(a):\n    return a > 1\n", ("compare",)).sites[0]
    after = found("X = 1\n\ndef f(a):\n    return a > 1\n", ("compare",)).sites[0]
    assert (before.enclosing, before.original, before.ordinal) == (
        after.enclosing,
        after.original,
        after.ordinal,
    )
    assert before.site_id != after.site_id


def test_the_same_file_gives_the_same_sites_in_the_same_order():
    text = "def f(a, b):\n    return a > 1 and b < 2 or not a\n"
    assert found(text).sites == found(text).sites


def test_every_wekufe_of_a_risky_spell_still_parses():
    text = "match x:\n    case 1:\n        a = 1\n    case 2:\n        a = 2\n"
    src = Source(text)
    for s in found(text, ("arm",)).sites:
        ast.parse(src.splice(s.start.byte, s.end.byte, s.replacement))


def test_every_other_wekufe_parses_too():
    # The cheap spells skip the parse, so this is the check that they never need it.
    text = (
        "def f(a, xs, ys):\n    if a >= 1 and not (a < 9) or a in xs:\n        return a is not None\n"
        "    while xs:\n        xs.pop()\n    return f'{a > 2}', 'x', 0, True, [y for y in ys if y != 1]\n"
    )
    src = Source(text)
    result = found(text).sites
    assert len(result) > 10
    for s in result:
        ast.parse(src.splice(s.start.byte, s.end.byte, s.replacement))


def test_the_type_a_cast_names_is_not_a_value():
    text = 'import typing as t\nx = t.cast("t.IO[t.Any]", y, 3)\nT = TypeVar("T", bound="Base")\nz = cast(int, "s")\n'
    # The first argument of `cast` and `TypeVar` names a type; the rest are values.
    assert [o for o, _ in of("literal", text)] == ["3", '"Base"', '"s"']
