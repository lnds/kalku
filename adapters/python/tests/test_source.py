import pytest

from kalku_python.source import Position, Source


def test_a_column_is_counted_in_characters_and_a_byte_in_bytes():
    # `é` is two bytes and one character: the counts part company here.
    src = Source("x = 'é'; y = 1\n")
    assert src.byte_at(1, 9) == 9
    assert src.position(10) == Position(line=1, col=10, byte=10)
    assert src.position(9) == Position(line=1, col=9, byte=9)


def test_the_second_line_counts_from_its_own_start():
    src = Source("ab\ncd\n")
    assert src.byte_at(2, 1) == 4
    assert src.position(4) == Position(2, 2, 4)
    assert src.position(3) == Position(2, 1, 3)


def test_the_first_byte_the_last_and_the_end_are_positions():
    src = Source("ab\ncd")
    assert src.position(0) == Position(1, 1, 0)
    assert src.position(2) == Position(1, 3, 2)
    assert src.position(5) == Position(2, 3, 5)


def test_crlf_and_lone_cr_end_a_line_like_lf():
    src = Source("a\r\nb\rc\n")
    assert src.byte_at(2, 0) == 3
    assert src.byte_at(3, 0) == 5
    assert src.position(5) == Position(3, 1, 5)


@pytest.mark.parametrize(("line", "col"), [(0, 0), (9, 0), (1, 99), (-1, 0)])
def test_a_position_outside_the_text_is_none_not_a_guess(line, col):
    assert Source("ab\n").byte_at(line, col) is None


def test_the_end_of_the_text_is_a_position():
    assert Source("ab\n").byte_at(1, 3) == 3
    assert Source("ab\n").byte_at(2, 0) == 3
    assert Source("ab").byte_at(1, 2) == 2


def test_a_slice_holds_exactly_the_span_and_refuses_what_is_not_text():
    src = Source("a é b")
    assert src.slice(0, 1) == "a"
    assert src.slice(2, 4) == "é"
    assert src.slice(3, 4) is None  # half a character
    assert src.slice(4, 2) is None
    assert src.slice(-1, 2) is None
    assert src.slice(0, 99) is None
    assert src.slice(2, 2) == ""


def test_splicing_replaces_exactly_the_span_in_bytes():
    src = Source("if a >= 10 {}é")
    assert src.splice(5, 7, ">") == "if a > 10 {}é"
    assert src.splice(5, 7, "") == "if a  10 {}é"
    assert Source("é = 1").splice(5, 6, "2") == "é = 2"


def test_a_byte_in_the_middle_of_a_character_is_a_position_not_an_error():
    # Half of `é`: the column still counts a character, as the replacement for what is cut.
    assert Source("é = 1").position(1) == Position(1, 2, 1)
    assert Source("aé").position(2) == Position(1, 3, 2)


def test_a_position_is_a_value_that_cannot_be_changed():
    import dataclasses

    with pytest.raises(dataclasses.FrozenInstanceError):
        Position(1, 1, 0).line = 2


def test_an_empty_slice_at_either_end_is_the_empty_text():
    src = Source("abc")
    assert src.slice(0, 0) == "" and src.slice(3, 3) == ""
    assert src.slice(0, 3) == "abc"
    assert src.slice(3, 2) is None
