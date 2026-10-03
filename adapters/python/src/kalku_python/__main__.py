"""The Python kalku on stdio."""

from __future__ import annotations

import os
import sys

from .service import Service


def main() -> int:
    # stdout is the protocol. What anything prints by accident goes to stderr,
    # and the protocol keeps a descriptor of its own.
    protocol_out = os.fdopen(os.dup(1), "wb", buffering=0)
    os.dup2(2, 1)
    sys.dont_write_bytecode = True
    return Service(0, protocol_out).serve()


if __name__ == "__main__":
    sys.exit(main())
