"""Positions in a source file, in the three ways they are counted.

`ast` reports a line (1-based) and a column that is an offset in UTF-8 bytes.
The protocol wants a line (1-based), a column (1-based, in characters) and a
byte offset (0-based) from the start of the file, and a wekufe is spliced by
bytes. This is the one place those meet, so a site cannot disagree with
itself about where it is.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

_LINE_END = re.compile(rb"\r\n|\r|\n")


@dataclass(frozen=True)
class Position:
    line: int
    col: int
    byte: int


class Source:
    """A source file's bytes, with where each line begins."""

    def __init__(self, text: str):
        self.text = text
        self.data = text.encode("utf-8")
        starts = [0]
        starts.extend(m.end() for m in _LINE_END.finditer(self.data))
        self._starts = starts

    def byte_at(self, line: int, byte_col: int) -> int | None:
        """The byte offset of what `ast` calls `(line, col_offset)`."""
        if line < 1 or line > len(self._starts):
            return None
        at = self._starts[line - 1] + byte_col
        return at if at <= len(self.data) else None

    def position(self, byte: int) -> Position:
        """The protocol's position for a byte offset."""
        lo, hi = 0, len(self._starts) - 1
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if self._starts[mid] <= byte:
                lo = mid
            else:
                hi = mid - 1
        start = self._starts[lo]
        col = len(self.data[start:byte].decode("utf-8", errors="replace")) + 1
        return Position(line=lo + 1, col=col, byte=byte)

    def slice(self, start: int, end: int) -> str | None:
        """The text between two byte offsets, when both fall on characters."""
        if not 0 <= start <= end <= len(self.data):
            return None
        try:
            return self.data[start:end].decode("utf-8")
        except UnicodeDecodeError:
            return None

    def splice(self, start: int, end: int, replacement: str) -> str:
        """The text with `start..end` replaced by `replacement`."""
        out = self.data[:start] + replacement.encode("utf-8") + self.data[end:]
        return out.decode("utf-8")
