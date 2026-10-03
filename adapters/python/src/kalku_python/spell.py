"""The spells of the shared catalogue, by their wire names.

A language may add spells, but a new one gets a shared name through the
protocol and not an ad-hoc string, so this is the closed set the protocol
states and nothing of Python's own.
"""

from __future__ import annotations

ALL = ("arm", "compare", "connect", "negate", "literal", "call", "await", "supervise", "foreign")

# What this kalku can cast. `await` and `supervise` are concurrency spells for
# the BEAM, and `foreign` is for a runtime with a native boundary; a spell is
# announced when it is true.
CAST = ("arm", "compare", "connect", "negate", "literal", "call")


def parse(name: str) -> str | None:
    """The spell a wire name is, or None when it is not one."""
    return name if name in ALL else None
