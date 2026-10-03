"""Answering requests.

One request per line in, one reply per line out, and nothing else may reach
stdout: the kaikai side banishes a worker for a line it cannot read.

The service is a single thread. While a cast runs it waits on two things at
once, the child that is running the tests and the channel the requests come
from, so that an `abort` is heard while the cast is still running; and
because there is no second thread there is nothing to be holding a lock when
the child is forked.
"""

from __future__ import annotations

import contextlib
import json
import os
import select
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from . import __version__, coverage, framing, project, protocol, runner, sites, source
from .protocol import (
    Abort,
    Baseline,
    Cast,
    DecodeError,
    Delegate,
    Hello,
    Prepare,
    Reload,
    Reset,
    Shutdown,
)
from .protocol import Sites as SitesRequest

MINIMUM = (3, 12)

# A message of a failing test, as long as it is worth saying.
_WARM_LIMIT = 3000


@dataclass
class _Session:
    ids: list[str] = field(default_factory=list)
    files: dict[str, str] = field(default_factory=dict)
    prepared: bool = False


class Service:
    def __init__(self, stdin_fd: int, out, adapter: str = __version__):
        self.stdin_fd = stdin_fd
        self.out = out
        self.adapter = adapter
        self.hello: Hello | None = None
        self.session = _Session()
        self._buffer = b""
        self._dropping = False
        # Lines read but not yet served, in the order they came.
        self._queue: list[Any] = []
        self._aborted: set[int] = set()
        self._eof = False

    # ---- reading ------------------------------------------------------------

    def _feed(self) -> None:
        """Take what has arrived on the channel, and note any `abort` in it."""
        data = os.read(self.stdin_fd, 1 << 20)
        if not data:
            self._eof = True
            return
        self._buffer += data
        *lines, self._buffer = self._buffer.split(b"\n")
        for raw in lines:
            line = self._line(raw)
            self._queue.append(line)
            if isinstance(line, str) and '"abort"' in line:
                with contextlib.suppress(DecodeError):
                    _, request = protocol.decode(line)
                    if isinstance(request, Abort):
                        self._aborted.add(request.cast)
        # A line that never ends is not held: it is dropped as it grows.
        if len(self._buffer) > protocol.MAX_LINE:
            self._buffer = b""
            self._dropping = True

    def _line(self, raw: bytes):
        if self._dropping or len(raw) > protocol.MAX_LINE:
            self._dropping = False
            return framing.TOO_LONG
        return framing._finish(bytearray(raw), False)

    def next_line(self):
        """The next request line, `TOO_LONG`, or None at end of input."""
        while not self._queue:
            if self._eof:
                return None
            select.select([self.stdin_fd], [], [])
            self._feed()
        return self._queue.pop(0)

    # ---- the loop -----------------------------------------------------------

    def serve(self) -> int:
        while True:
            line = self.next_line()
            if line is None:
                return 0
            replies, stop = self.handle(line)
            for reply in replies:
                self._say(reply)
            if stop:
                return 0

    def _say(self, reply: str) -> None:
        self.out.write(reply.encode("utf-8") + b"\n")
        self.out.flush()

    def handle(self, line) -> tuple[list[str], bool]:
        """One line in, the replies to say, and whether to leave."""
        if line is framing.TOO_LONG:
            return [
                self._refuse(
                    DecodeError(
                        protocol.LINE_TOO_LONG, f"a line longer than {protocol.MAX_LINE} bytes"
                    )
                )
            ], False
        try:
            ident, request = protocol.decode(line)
        except DecodeError as e:
            return [self._refuse(e)], False
        if isinstance(request, Hello):
            return [self._greet(ident, request)], False
        if isinstance(request, Shutdown):
            return [protocol.bye(ident)], True
        if isinstance(request, Abort):
            return [protocol.aborted(ident, request.cast, True)], False
        if self.hello is None:
            return [protocol.error(ident, "not_ready", "`hello` has to come first", False)], False
        if isinstance(request, SitesRequest):
            return [self._sites(ident, request)], False
        if isinstance(request, Prepare):
            return [self._prepare(ident)], False
        if isinstance(request, Baseline):
            return [self._baseline(ident)], False
        if isinstance(request, Cast):
            return self._cast(ident, request), False
        name = {Reset: "reset", Reload: "reload", Delegate: "delegate"}[type(request)]
        return [
            protocol.error(ident, "not_implemented", f"this kalku does not answer `{name}`", True)
        ], False

    def _refuse(self, e: DecodeError) -> str:
        return protocol.error(
            e.id if e.id is not None else 0, "bad_request", f"{e.kind}: {e.detail}", False
        )

    # ---- hello --------------------------------------------------------------

    def _greet(self, ident: int, hello: Hello) -> str:
        if hello.protocol != protocol.PROTOCOL:
            return protocol.error(
                ident,
                "protocol_mismatch",
                f"kalku speaks protocol {protocol.PROTOCOL}, kaikai side speaks {hello.protocol}",
                True,
            )
        if sys.version_info < MINIMUM:
            return protocol.error(
                ident,
                "unsupported_toolchain",
                f"Python {sys.version.split()[0]} is older than 3.12, the first release with "
                "`sys.monitoring`, which per-test coverage needs",
                True,
            )
        try:
            import pytest
        except ImportError:
            return protocol.error(
                ident,
                "toolchain_missing",
                f"pytest is not installed for {sys.executable}; kalku runs the project's own "
                "interpreter, so install pytest in the project's environment",
                True,
            )
        self.hello = hello
        runtime = f"CPython {sys.version.split()[0]}, pytest {pytest.__version__}"
        return protocol.ready(ident, self.adapter, runtime, ["cast", "abort", "per_test_coverage"])

    # ---- sites --------------------------------------------------------------

    def _sites(self, ident: int, request: SitesRequest) -> str:
        assert self.hello is not None
        root = Path(self.hello.root)
        found: list[dict[str, Any]] = []
        skipped: list[dict[str, Any]] = []
        for file in request.files:
            reason, message = self._search(root, file, request, found)
            if reason is not None:
                skipped.append({"file": file, "reason": reason, "message": message})
        return protocol.sites_found(ident, found, skipped)

    def _search(
        self, root: Path, file: str, request: SitesRequest, found: list
    ) -> tuple[str | None, str]:
        if not file.endswith(".py"):
            return "not_python", f"`{file}` is not a Python source file"
        if project.is_test_file(file):
            return "test_file", f"`{file}` is part of the suite, which is not measured"
        try:
            text = (root / file).read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as e:
            return "unreadable", f"cannot read `{file}`: {e}"
        try:
            result = sites.find(file, text, request.spells, request.exclude_calls)
        except sites.ParseError as e:
            return "parse_error", str(e)
        found.extend(protocol.site_json(s) for s in result.sites)
        return None, ""

    # ---- prepare ------------------------------------------------------------

    def _work(self) -> Path:
        assert self.hello is not None
        return Path(self.hello.reni) / "work" / str(self.hello.worker)

    def _prepare(self, ident: int) -> str:
        assert self.hello is not None
        started = time.monotonic()
        root, reni = Path(self.hello.root), Path(self.hello.reni)
        work = self._work()
        try:
            project.sync_tree(root, work, skip=reni)
        except OSError as e:
            return protocol.error(ident, "prepare_failed", f"cannot copy the project: {e}", True)
        events = self._run(runner.Spec(str(work), "collect"))
        if events is None:
            return protocol.error(ident, "aborted", "the kalku was asked to stop", True)
        problem = _problem(events)
        ids = [i for e in events if e.get("e") == "collected" for i in e["ids"]]
        if problem is not None:
            return protocol.error(ident, "prepare_failed", problem, True)
        self.session = _Session(ids=ids, files={i: i.split("::", 1)[0] for i in ids}, prepared=True)
        modules = len(set(self.session.files.values()))
        return protocol.prepared(ident, int((time.monotonic() - started) * 1000), modules)

    # ---- baseline -----------------------------------------------------------

    def _baseline(self, ident: int) -> str:
        assert self.hello is not None
        if not self.session.prepared:
            return protocol.error(ident, "not_ready", "`prepare` has to come first", False)
        started = time.monotonic()
        work = self._work()
        events = self._run(runner.Spec(str(work), "baseline"))
        if events is None:
            return protocol.error(ident, "aborted", "the kalku was asked to stop", True)
        problem = _problem(events)
        if problem is not None:
            return protocol.error(ident, "baseline_failed", problem, True)
        tests, failures, per_test = [], [], {}
        for e in events:
            if e.get("e") != "result":
                continue
            tests.append(
                {
                    "test": e["id"],
                    "file": e["id"].split("::", 1)[0],
                    "duration_ms": e["duration_ms"],
                }
            )
            if e["outcome"] == "failed":
                failures.append({"test": e["id"], "message": e["message"] or "the test failed"})
            per_test[e["id"]] = {f: set(n) for f, n in e.get("lines", {}).items()}
        self._warm(events)
        attributed = coverage.expand(per_test, lambda f: _read(work / f))
        entries = coverage.invert(attributed)
        return protocol.baseline_done(
            ident,
            int((time.monotonic() - started) * 1000),
            tests,
            failures,
            self._spoken(entries),
        )

    def _spoken(self, entries: list[dict]) -> tuple[str, Any]:
        """Inline when small, and in a file in the reni when it is not."""
        assert self.hello is not None
        text = json.dumps(entries, separators=(",", ":"))
        if len(text.encode()) <= self.hello.inline_limit_bytes:
            return "inline", entries
        directory = Path(self.hello.reni) / "coverage"
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / f"{self.hello.worker}.json"
        path.write_text(text + "\n", encoding="utf-8")
        return "path", str(path)

    def _warm(self, events: list[dict]) -> None:
        """Import what the tests imported from outside the project, so that
        every later child starts with it in memory."""
        for e in events:
            if e.get("e") != "done":
                continue
            for name in e.get("warm", [])[:_WARM_LIMIT]:
                if name not in sys.modules:
                    with contextlib.suppress(Exception):
                        __import__(name)

    # ---- cast ---------------------------------------------------------------

    def _cast(self, ident: int, request: Cast) -> list[str]:
        if not self.session.prepared:
            return [protocol.error(ident, "not_ready", "`prepare` has to come first", False)]
        for test in request.tests:
            if test not in self.session.files:
                return [
                    protocol.error(
                        ident, "unknown_test", f"`{test}` is not a test this kalku knows", False
                    )
                ]
        site = request.site
        work = self._work()
        file = site["file"]
        try:
            original = (work / file).read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as e:
            return [
                protocol.error(
                    ident, "bad_request", f"cannot read `{file}` in the reni: {e}", False
                )
            ]
        src = source.Source(original)
        start, end = site["span"]["start"]["byte"], site["span"]["end"]["byte"]
        # A site is only valid for the text it was found in.
        if site.get("original") is not None and src.slice(start, end) != site["original"]:
            return [
                protocol.error(
                    ident, "bad_request", f"`{file}` is not the text this site was found in", False
                )
            ]
        mutated = src.splice(start, end, site["replacement"])
        started = time.monotonic()
        try:
            compile(mutated, str(work / file), "exec", dont_inherit=True)
        except (SyntaxError, ValueError) as e:
            return [
                protocol.cast_done(
                    ident, request.wekufe, "compile_error", _ms(started), message=_syntax(e)
                )
            ]
        if ident in self._aborted:
            return []
        events = self._run(
            runner.Spec(str(work), "cast", list(request.tests), runner.Mutation(file, mutated)),
            cast=ident,
        )
        if events is None:
            return []
        return [self._verdict(ident, request, events, started)]

    def _verdict(self, ident: int, request: Cast, events: list[dict], started: float) -> str:
        results = {e["id"]: e for e in events if e.get("e") == "result"}
        done = next((e for e in events if e.get("e") == "done"), None)
        # A test that failed is what kills, in the order the tests ran.
        for e in events:
            if e.get("e") == "result" and e["outcome"] == "failed":
                return protocol.cast_done(
                    ident, request.wekufe, "killed", _ms(started), killed_by=e["id"]
                )
        problem = _problem(events)
        if done is None or problem is not None:
            # A child that never finished, or a suite that would not load,
            # is not a test that passed: what the wekufe broke is what killed it.
            started_ids = [e["id"] for e in events if e.get("e") == "start"]
            culprit = (started_ids[-1:] or request.tests[:1] or ["?"])[0]
            return protocol.cast_done(
                ident, request.wekufe, "killed", _ms(started), killed_by=culprit
            )
        ran = [t for t in request.tests if results.get(t, {}).get("outcome") == "passed"]
        if not ran:
            return protocol.error(
                ident, "unknown_test", "none of the requested tests ran and passed", False
            )
        return protocol.cast_done(ident, request.wekufe, "survived", _ms(started))

    # ---- waiting on a child ---------------------------------------------------

    def _run(self, spec: runner.Spec, cast: int | None = None) -> list[dict] | None:
        """Run a child to its end and say what it said; None if it was stopped."""
        run = runner.spawn(spec)
        events: list[dict] = []
        stopped = False
        try:
            while True:
                if cast is not None and cast in self._aborted:
                    stopped = True
                    break
                if self._eof:
                    stopped = True
                    break
                ready, _, _ = select.select([run.fd, self.stdin_fd], [], [])
                if run.fd in ready:
                    got = run.read()
                    if got is None:
                        break
                    events.extend(got)
                if self.stdin_fd in ready and not self._eof:
                    self._feed()
        finally:
            if stopped:
                run.kill()
            run.finish()
        return None if stopped else events


# ---- small things -----------------------------------------------------------


def _problem(events: list[dict]) -> str | None:
    """Why a suite could not be run at all, from what its child said."""
    for e in events:
        if e.get("e") == "crashed":
            return e["message"]
        if e.get("e") == "collect_error":
            return f"{e['id'] or 'collection'}: {e['message']}"
    return None


def _ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)


def _read(path: Path) -> str | None:
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return None


def _syntax(e: Exception) -> str:
    if isinstance(e, SyntaxError):
        return f"SyntaxError: {e.msg} (line {e.lineno})"
    return f"{type(e).__name__}: {e}"
