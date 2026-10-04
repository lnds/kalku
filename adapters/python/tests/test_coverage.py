import dataclasses

import pytest

from kalku_python import coverage

# Lines: 1-4 constants, 7-9 the decorator and signature of `first`, 10-11 its
# body, 14-15 the class `Box` and its attribute, 17-18 `grow`, 20-21 `shrink`,
# 24-25 `second`.
SOURCE = """\
LIMIT = 10
NAMES = [
    "a",
]


@decorate(retries=3)
def first(a, b=1,
          c=2):
    x = a + b
    return x


class Box:
    size = 3

    def grow(self, by=4):
        return self.size + by

    def shrink(self):
        return 0


def second():
    return 1
"""


def lines_of(text, ran):
    return coverage.expand({"t": {"m.py": set(ran)}}, lambda f: text)["t"]["m.py"]


def test_the_header_of_a_function_belongs_to_the_tests_that_ran_its_body():
    got = lines_of(SOURCE, {10})
    assert {7, 8, 9} <= got
    assert not {17, 20} & got


def test_a_test_that_ran_nothing_of_a_function_is_not_credited_with_its_header():
    # Only `second` ran: the header of `first` is not its business.
    assert not {7, 8, 9} & lines_of(SOURCE, {25})


def test_the_attributes_of_a_class_belong_to_the_tests_that_ran_any_method():
    got = lines_of(SOURCE, {18})
    assert {14, 15} <= got


def test_a_method_header_goes_with_its_own_body_and_not_a_sibling_s():
    got = lines_of(SOURCE, {18})
    assert 17 in got
    assert 20 not in got


def test_the_constants_of_a_module_belong_to_any_test_that_ran_the_module():
    assert {1, 2, 3, 4} <= lines_of(SOURCE, {25})


def test_a_file_that_cannot_be_read_or_parsed_adds_nothing():
    assert lines_of(None, {3}) == {3}
    assert coverage.expand({"t": {"m.py": {3}}}, lambda f: "def (:")["t"]["m.py"] == {3}


def test_lines_are_turned_around_into_the_tests_that_reached_them():
    got = coverage.invert({"t1": {"a.py": {1, 2}}, "t2": {"a.py": {2}, "b.py": {7}}})
    assert got == [
        {"file": "a.py", "line": 1, "tests": ["t1"]},
        {"file": "a.py", "line": 2, "tests": ["t1", "t2"]},
        {"file": "b.py", "line": 7, "tests": ["t2"]},
    ]
    assert coverage.invert({}) == []


NESTED = '''\
import os

try:
    import fast
except ImportError:
    fast = None

if os.name == "nt":
    def where():
        return "win"
else:
    def where():
        return "posix"

with open_it() as handle:
    CONSTANT = 1


@register
class Box:
    """doc"""

    size = 3

    class Inner:
        depth = 4

        def peek(self):
            return self.depth

    async def fetch(self,
                    url="x"):
        def local():
            return 1
        return local()


for i in range(2):
    LOOP = i

while False:
    NEVER = 1
'''


def test_a_function_defined_inside_a_module_level_block_goes_with_its_own_body():
    # `where` in the `else` has its body on line 13 and its header on line 12.
    got = lines_of(NESTED, {13})
    assert 12 in got
    # The `if` branch's `where` was never the one defined.
    assert 9 not in got


def test_the_blocks_of_a_module_belong_to_the_module():
    got = lines_of(NESTED, {13})
    assert {1, 3, 4, 5, 6, 8, 11, 15, 16, 38, 39, 41, 42} <= got


def test_a_decorated_class_and_its_nested_class_share_attributes_with_their_methods():
    # `Inner.peek` ran (line 29): its header (28), the class `Inner` and its
    # attribute (25, 26), and the decorator, header and attribute of the outer
    # class `Box` (19, 20, 21, 23).
    got = lines_of(NESTED, {29})
    assert {28, 25, 26, 19, 20, 21, 23} <= got
    assert 31 not in got


def test_the_header_of_an_async_method_spanning_two_lines_goes_with_its_body():
    got = lines_of(NESTED, {34})
    assert {31, 32} <= got


def test_nothing_is_credited_to_a_test_that_ran_nothing_of_the_file():
    assert coverage.expand({"t": {"m.py": set()}}, lambda f: NESTED)["t"]["m.py"] == set()


def test_each_file_of_a_test_is_expanded_by_its_own_source():
    sources = {
        "a.py": "X = 1\n\ndef f():\n    return 2\n",
        "b.py": "Y = 1\n\ndef g():\n    return 2\n",
    }
    got = coverage.expand({"t": {"a.py": {4}, "b.py": set()}}, sources.get)["t"]
    assert got["a.py"] == {1, 3, 4} and got["b.py"] == set()


def test_a_file_is_read_once_however_many_tests_ran_it():
    reads = []

    def read(file):
        reads.append(file)
        return "X = 1\n\ndef f():\n    return 2\n"

    coverage.expand({"t1": {"a.py": {4}}, "t2": {"a.py": {4}}, "t3": {"a.py": {4}}}, read)

    assert reads == ["a.py"]


def test_a_decorated_function_header_includes_every_decorator_line():
    text = "@a\n@b(1,\n   2)\ndef f(x):\n    return x\n"
    assert lines_of(text, {5}) == {1, 2, 3, 4, 5}


CONFIG = "LIMIT = 9\nNAMES = ['a']\n\n\nclass Settings:\n    mode = 'fast'\n\n    def run(self):\n        return 1\n"


def expanded(per_test, depends, files=("config.py",), wanted=None):
    return coverage.expand(per_test, lambda f: CONFIG, depends=depends, files=files, wanted=wanted)


def test_a_test_that_only_reads_a_constant_is_credited_with_it_through_what_it_imports():
    per_test = {"tests/test_a.py::reads": {}, "tests/test_b.py::runs": {"config.py": {9}}}

    got = expanded(per_test, lambda f: {"tests/test_a.py"})

    # The reader ran no line of the module; it depends on it, so the module's
    # own lines and the class attribute are its too.
    assert got["tests/test_a.py::reads"] == {"config.py": {1, 2, 5, 6}}
    # The test that ran the method has them by running the module.
    assert {1, 2, 5, 6, 8, 9} <= got["tests/test_b.py::runs"]["config.py"]


def test_a_test_that_does_not_depend_on_the_module_is_not_credited_with_it():
    per_test = {"tests/test_a.py::x": {}, "tests/test_b.py::y": {}}

    got = expanded(per_test, lambda f: {"tests/test_a.py"})

    assert got["tests/test_b.py::y"] == {}


def test_only_the_lines_that_have_something_to_measure_are_shared():
    per_test = {"tests/test_a.py::reads": {}}

    got = expanded(per_test, lambda f: {"tests/test_a.py"}, wanted=lambda f: {1})

    assert got["tests/test_a.py::reads"] == {"config.py": {1}}


def test_a_module_nothing_ran_is_still_considered_when_it_is_named():
    per_test = {"tests/test_a.py::reads": {"other.py": {1}}}

    got = expanded(per_test, lambda f: {"tests/test_a.py"} if f == "config.py" else set())

    assert "config.py" in got["tests/test_a.py::reads"]


def test_a_module_that_nothing_depends_on_adds_nothing():
    per_test = {"tests/test_a.py::x": {}}
    assert expanded(per_test, lambda f: set()) == {"tests/test_a.py::x": {}}


def test_without_a_dependency_map_only_what_ran_is_expanded():
    assert coverage.expand({"t": {"config.py": {9}}}, lambda f: CONFIG)["t"]["config.py"] >= {9}


TRY = """\
try:
    def in_body():
        return 1
except ValueError:
    def in_handler():
        return 2
else:
    def in_else():
        return 3
finally:
    def in_final():
        return 4
"""
TRY_OWN = {1, 4, 7, 10}


def test_a_function_in_any_block_of_a_try_goes_with_its_own_body_and_no_other():
    # The `try` lines themselves are the module's; each function's header goes
    # only with the body that ran.
    assert lines_of(TRY, {3}) == TRY_OWN | {2, 3}
    assert lines_of(TRY, {6}) == TRY_OWN | {5, 6}
    assert lines_of(TRY, {9}) == TRY_OWN | {8, 9}
    assert lines_of(TRY, {12}) == TRY_OWN | {11, 12}


def test_a_statement_right_after_a_function_is_the_modules_and_not_the_functions():
    text = "def f():\n    return 1\nX = 2\n"
    assert lines_of(text, {2}) == {1, 2, 3}


def test_a_rule_cannot_be_changed_once_made():
    rule = coverage.Rule(1, 2, frozenset({1}))
    with pytest.raises(dataclasses.FrozenInstanceError):
        rule.first = 5


def test_a_test_that_ran_only_the_first_line_of_a_module_is_credited_with_the_rest():
    assert lines_of("A = 1\nB = 2\n", {1}) == {1, 2}
