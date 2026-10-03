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
