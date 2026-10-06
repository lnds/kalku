"""The Python kalku.

It speaks the kalku protocol on stdio like any other, and does the one thing
a kalku is for: it knows the language. Sites come from `ast`, which is
Python's own parser, and never from a scan of the text. Planning, scheduling,
caching and scoring belong to the kaikai side.

It uses the standard library and nothing else: it runs inside the project's
own interpreter, next to the project's own packages, and must not choose
which version of anything they get.
"""

__version__ = "0.9.1"
