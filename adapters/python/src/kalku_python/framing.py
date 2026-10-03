"""Newline-delimited framing with a ceiling.

A line longer than the limit is discarded up to the next newline without
being buffered, and the next line is read as if nothing had happened: a peer
that sends gigabytes without a newline must not be able to make this process
hold them, and one oversized message must not cost the ones after it.
"""

from __future__ import annotations

from typing import BinaryIO

TOO_LONG = object()

_CHUNK = 65536


def read_line(stream: BinaryIO, limit: int):
    """The next line as text, `TOO_LONG`, or None at end of input."""
    held = bytearray()
    too_long = False
    seen_any = False
    while True:
        # One byte past the limit is enough to know the line is too long.
        chunk = stream.readline(_CHUNK)
        if not chunk:
            return _finish(held, too_long) if seen_any else None
        seen_any = True
        ended = chunk.endswith(b"\n")
        body = chunk[:-1] if ended else chunk
        if not too_long and len(held) + len(body) <= limit:
            held.extend(body)
        else:
            too_long = True
            held.clear()
        if ended:
            return _finish(held, too_long)


def _finish(held: bytearray, too_long: bool):
    if too_long:
        return TOO_LONG
    try:
        text = held.decode("utf-8")
    except UnicodeDecodeError:
        # The protocol is UTF-8; bytes that are not are not JSON.
        return "\x00"
    return text.rstrip("\r")
