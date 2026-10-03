import io

import pytest

from kalku_python import framing
from kalku_python.framing import TOO_LONG, read_line


def lines(data: bytes, limit: int):
    stream = io.BytesIO(data)
    out = []
    while (line := read_line(stream, limit)) is not None:
        out.append(line)
    return out


def test_lines_are_split_on_newlines_and_the_last_may_have_none():
    assert lines(b"ab\ncd\nef", 100) == ["ab", "cd", "ef"]
    assert lines(b"", 100) == []
    assert lines(b"\n", 100) == [""]


def test_a_carriage_return_before_the_newline_is_not_part_of_the_line():
    assert lines(b"ab\r\ncd\r\n", 100) == ["ab", "cd"]


def test_a_line_of_exactly_the_limit_is_whole_and_one_byte_more_is_too_long():
    assert lines(b"abc\n", 3) == ["abc"]
    assert lines(b"abcd\n", 3) == [TOO_LONG]
    assert lines(b"abc", 3) == ["abc"]
    assert lines(b"abcd", 3) == [TOO_LONG]


def test_one_oversized_line_costs_only_itself():
    assert lines(b"abcdefgh\nok\n", 3) == [TOO_LONG, "ok"]
    assert lines(b"ab\nabcdefgh\nok\n", 3) == ["ab", TOO_LONG, "ok"]


def test_a_line_longer_than_one_read_is_still_one_line_and_still_bounded(monkeypatch):
    monkeypatch.setattr(framing, "_CHUNK", 4)
    assert lines(b"abcdefghij\nok\n", 100) == ["abcdefghij", "ok"]
    assert lines(b"abcdefghij\nok\n", 6) == [TOO_LONG, "ok"]
    assert lines(b"abcdef\nok\n", 6) == ["abcdef", "ok"]


def test_bytes_that_are_not_utf8_are_a_line_that_is_not_json():
    assert lines(b"\xff\xfe\nok\n", 100) == ["\x00", "ok"]


@pytest.mark.parametrize("text", ["é", "日本語", "a b"])
def test_multibyte_text_is_read_whole(text):
    assert lines((text + "\n").encode(), 100) == [text]


def test_what_is_read_in_one_go_is_bounded_and_a_dropped_line_is_not_held():
    import tracemalloc

    assert framing._CHUNK == 65536
    # The input is built before anything is measured: it is not what is under test.
    stream = io.BytesIO(b"a" * (8 * 1024 * 1024) + b"\nok\n")
    tracemalloc.start()
    try:
        before = tracemalloc.get_traced_memory()[0]
        out = [read_line(stream, 100), read_line(stream, 100)]
        peak = tracemalloc.get_traced_memory()[1] - before
    finally:
        tracemalloc.stop()

    assert out == [TOO_LONG, "ok"]
    # The line was never held: the peak is a few chunks of the reader's own and
    # not the eight megabytes of the line.
    assert peak < 1024 * 1024
