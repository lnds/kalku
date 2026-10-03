"""The Python kalku on stdio."""

from __future__ import annotations

import os
import sys

from .service import Service


def main(stdin_fd: int = 0, stdout_fd: int = 1) -> int:
    """Serve the protocol on two descriptors until shutdown or end of input."""
    # stdout is the protocol. The protocol keeps a descriptor of its own, and
    # what anything prints by accident goes to stderr instead.
    protocol_out = os.fdopen(os.dup(stdout_fd), "wb", buffering=0)
    if stdout_fd == 1:
        os.dup2(2, 1)
    sys.dont_write_bytecode = True
    try:
        return Service(stdin_fd, protocol_out).serve()
    finally:
        protocol_out.close()


if __name__ == "__main__":
    sys.exit(main())
