"""Module docs: a > 1 and 'text'."""

from __future__ import annotations

import typing
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    pass

__all__ = ["f", "Thing"]

__version__ = "1.2.3"


class Thing:
    """A thing with 3 parts."""

    count: int = 0
    name: str = "thing"

    def method(self, a: int = 1) -> int | None:
        """Docs with a >= b."""
        return a


def f(a: int, b: typing.Literal["x", "y"] = "x") -> bool:
    # a comment with > and 7
    return a > b  # trailing 8


def g(flag):
    match flag:
        case "on":
            return True
        case 1:
            return False


if __name__ == "__main__":
    f(1, "y") > 2
