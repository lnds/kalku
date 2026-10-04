"""Running the project's tests in a child process.

Every run of the suite happens in a forked child. The parent is the warm
part: it has imported the interpreter's own machinery and pytest, and after a
baseline it also imports what the tests import from outside the project, so
that each child starts with those already in memory. A child is thrown away
when it is done, which is what isolates a wekufe from the next one with no
state to reset, and what makes `abort` a kill.

The wekufe is applied in memory. The module it belongs to is served to the
importer from a string, so the copy of the project in the reni is never
written, and no bytecode cache can serve the original in its place.

Nothing here uses threads: forking a process that has them is how a child
inherits a lock nobody will ever release.
"""

from __future__ import annotations

import contextlib
import faulthandler
import importlib.abc
import importlib.machinery
import json
import os
import shlex
import signal
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from . import project

# The messages a child sends are one JSON object per line.
_MESSAGE_LIMIT = 2000

_BASE_ARGS = (
    "-p",
    "no:cacheprovider",
    "-p",
    "no:randomly",
    "-p",
    "no:xdist",
    "-p",
    "no:cov",
    "-q",
    "--no-header",
)


@dataclass
class Mutation:
    """A file of the project, as it should read for this run."""

    file: str
    source: str


@dataclass
class Spec:
    work: str
    # `collect`, `baseline` or `cast`.
    mode: str
    tests: list[str] = field(default_factory=list)
    mutation: Mutation | None = None


# ---- the parent's side ------------------------------------------------------


class Run:
    """A child and the pipe it reports on."""

    def __init__(self, pid: int, fd: int):
        self.pid = pid
        self.fd = fd
        self._buffer = b""
        self.finished = False

    def read(self) -> list[dict[str, Any]] | None:
        """What the child has said since last asked, or None when it has closed its end."""
        data = os.read(self.fd, 1 << 20)
        if not data:
            return None
        self._buffer += data
        *lines, self._buffer = self._buffer.split(b"\n")
        out = []
        for line in lines:
            with contextlib.suppress(ValueError):
                out.append(json.loads(line))
        return out

    def kill(self) -> None:
        """The child and everything it started."""
        with contextlib.suppress(ProcessLookupError, PermissionError):
            os.killpg(self.pid, signal.SIGKILL)

    def finish(self) -> int | None:
        """Close the pipe and reap the child; its exit status, or None when a signal ended it."""
        if self.finished:
            return None
        self.finished = True
        with contextlib.suppress(OSError):
            os.close(self.fd)
        try:
            _, status = os.waitpid(self.pid, 0)
        except ChildProcessError:
            return None
        return os.waitstatus_to_exitcode(status) if os.WIFEXITED(status) else None


def spawn(spec: Spec) -> Run:
    """Fork a child that runs `spec` and reports on a pipe."""
    read_fd, write_fd = os.pipe()
    sys.stdout.flush()
    sys.stderr.flush()
    pid = os.fork()
    if pid == 0:  # pragma: no cover - the child is measured by what it reports
        os.close(read_fd)
        _child(spec, write_fd)
        os._exit(0)
    os.close(write_fd)
    return Run(pid, read_fd)


# ---- the child's side -------------------------------------------------------


class _Out:
    def __init__(self, fd: int):
        self.fd = fd

    def send(self, message: dict[str, Any]) -> None:
        data = (json.dumps(message, separators=(",", ":")) + "\n").encode()
        view = memoryview(data)
        while view:
            view = view[os.write(self.fd, view) :]


def _child(spec: Spec, write_fd: int) -> None:  # pragma: no cover
    out = _Out(write_fd)
    try:
        os.setpgrp()
        _detach_stdio()
        _close_inherited(keep={write_fd})
        if os.environ.get("KALKU_PYTHON_DEBUG"):
            # A child that does not come back can be asked where it is:
            # `kill -USR1 <pid>` prints every thread's stack on stderr.
            faulthandler.register(signal.SIGUSR1, all_threads=True)
        sys.dont_write_bytecode = True
        os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
        work = Path(os.path.realpath(spec.work))
        os.chdir(work)
        sys.path[:0] = project.import_roots(work)
        _forget_project(work)
        if spec.mutation is not None:
            _install_mutation(work, spec.mutation)
        import pytest

        recorder = _Recorder(out, work, measuring=spec.mode == "baseline")
        addopts = project.sanitized_addopts(project.read_addopts(work))
        args = [*_BASE_ARGS, "-o", f"addopts={addopts}", f"--rootdir={work}"]
        args.extend(shlex.split(os.environ.get("KALKU_PYTEST_ARGS", "")))
        if spec.mode == "collect":
            args.append("--collect-only")
        elif spec.mode == "cast":
            args.extend(["-x", *spec.tests])
        code = pytest.main(args, plugins=[recorder])
        out.send({"e": "done", "exit": int(code), "warm": _warm_modules(work)})
    except BaseException as e:  # a crash of ours is a message, not silence
        with contextlib.suppress(Exception):
            out.send({"e": "crashed", "message": f"{type(e).__name__}: {e}"})
    finally:
        os._exit(0)


def _detach_stdio() -> None:  # pragma: no cover
    """The parent's stdout is the protocol: nothing a test prints may reach it."""
    quiet = os.open(os.devnull, os.O_RDWR)
    os.dup2(quiet, 0)
    os.dup2(quiet, 1)
    if not os.environ.get("KALKU_PYTHON_DEBUG"):
        os.dup2(quiet, 2)
    os.close(quiet)
    # The streams the interpreter prints through are rebuilt on those descriptors:
    # whatever they were wrapping may be a file the parent had open, and is closed.
    sys.stdin = sys.__stdin__ = open(0, encoding="utf-8", errors="replace", closefd=False)  # noqa: SIM115
    sys.stdout = sys.__stdout__ = open(1, "w", encoding="utf-8", errors="replace", closefd=False)  # noqa: SIM115
    sys.stderr = sys.__stderr__ = open(2, "w", encoding="utf-8", errors="replace", closefd=False)  # noqa: SIM115


def _close_inherited(keep: set[int]) -> None:  # pragma: no cover
    """Close every descriptor the parent had open but this child's own pipe.

    The parent keeps the protocol on a descriptor of its own, and a child that
    inherited it could write into the conversation: a test that prints to a
    descriptor it did not open, or a wekufe that makes it do so, would corrupt
    a channel that only the parent may speak on."""
    try:
        inherited = [int(name) for name in os.listdir("/dev/fd")]
    except (OSError, ValueError):
        inherited = list(range(3, 1024))
    for fd in inherited:
        if fd > 2 and fd not in keep:
            with contextlib.suppress(OSError):
                os.close(fd)


def _forget_project(work: Path) -> None:  # pragma: no cover
    """Import the project again, from the copy, whatever was imported before."""
    names = project.project_packages(work)
    for name in list(sys.modules):
        if name.split(".", 1)[0] in names:
            del sys.modules[name]
    importlib.invalidate_caches()


def _warm_modules(work: Path) -> list[str]:  # pragma: no cover
    """What this run imported that is not the project's: what a later child can
    have ready."""
    names = project.project_packages(work)
    root = str(work)
    keep = []
    for name, module in list(sys.modules.items()):
        if name.split(".", 1)[0] in names or name == "__main__":
            continue
        where = getattr(module, "__file__", None)
        if where and not os.path.realpath(where).startswith(root):
            keep.append(name)
    return keep


class _MutatedLoader(importlib.machinery.SourceFileLoader):
    def __init__(self, fullname: str, path: str, source: str):
        super().__init__(fullname, path)
        self._mutated = source

    def get_code(self, fullname: str):
        return compile(self._mutated, self.path, "exec", dont_inherit=True)

    def get_source(self, fullname: str) -> str:
        return self._mutated


class _MutatingFinder(importlib.abc.MetaPathFinder):
    def __init__(self, target: str, source: str):
        self.target = target
        self.source = source

    def find_spec(self, fullname, path=None, target=None):
        spec = importlib.machinery.PathFinder.find_spec(fullname, path)
        if spec is None or not spec.origin:
            return None
        if os.path.realpath(spec.origin) != self.target:
            return None
        spec.loader = _MutatedLoader(fullname, spec.origin, self.source)
        return spec


def _install_mutation(work: Path, mutation: Mutation) -> None:  # pragma: no cover
    target = os.path.realpath(work / mutation.file)
    sys.meta_path.insert(0, _MutatingFinder(target, mutation.source))
    importlib.invalidate_caches()


class _Recorder:  # pragma: no cover - exercised through a real pytest in a child
    """A pytest plugin that says, as it goes, what the suite is doing."""

    def __init__(self, out: _Out, work: Path, measuring: bool):
        self.out = out
        self.work = str(work)
        self.measuring = measuring
        self.outcome: dict[str, dict[str, Any]] = {}
        self.current: dict[str, set[int]] = {}
        self.tracked: dict[str, bool] = {}
        if measuring:
            self._start_monitoring()

    # ---- coverage -----------------------------------------------------------

    def _start_monitoring(self) -> None:
        mon = sys.monitoring
        self._mon = mon
        self._tool = _free_tool_id(mon)

        def line(code, number):
            name = code.co_filename
            ok = self.tracked.get(name)
            if ok is None:
                ok = self.tracked[name] = self._is_project_source(name)
            if not ok:
                return mon.DISABLE
            self.current.setdefault(name, set()).add(number)
            return mon.DISABLE

        mon.register_callback(self._tool, mon.events.LINE, line)
        mon.set_events(self._tool, mon.events.LINE)

    def _is_project_source(self, name: str) -> bool:
        if not name.endswith(".py") or not name.startswith(self.work + os.sep):
            return False
        relative = name[len(self.work) + 1 :]
        parts = relative.split(os.sep)
        if any(project.is_ignored(p) for p in parts[:-1]):
            return False
        return not project.is_test_file(relative)

    # ---- pytest hooks -------------------------------------------------------

    def pytest_collection_modifyitems(self, items):
        self.out.send({"e": "collected", "ids": [item.nodeid for item in items]})

    def pytest_collectreport(self, report):
        if report.failed:
            self.out.send({"e": "collect_error", "id": report.nodeid, "message": _text(report)})

    def pytest_runtest_logstart(self, nodeid, location):
        self.out.send({"e": "start", "id": nodeid})
        self.outcome[nodeid] = {"outcome": "passed", "duration": 0.0, "message": ""}
        if self.measuring:
            self.current = {}
            self._mon.restart_events()

    def pytest_runtest_logreport(self, report):
        entry = self.outcome.setdefault(
            report.nodeid, {"outcome": "passed", "duration": 0.0, "message": ""}
        )
        entry["duration"] += report.duration
        if report.failed:
            entry["outcome"] = "failed"
            entry["message"] = entry["message"] or _text(report)
        elif report.skipped and entry["outcome"] == "passed":
            entry["outcome"] = "xfailed" if hasattr(report, "wasxfail") else "skipped"
        if report.when == "teardown":
            message = {
                "e": "result",
                "id": report.nodeid,
                "outcome": entry["outcome"],
                "duration_ms": int(entry["duration"] * 1000),
                "message": entry["message"],
            }
            if self.measuring:
                relative = {
                    name[len(self.work) + 1 :]: sorted(numbers)
                    for name, numbers in self.current.items()
                }
                message["lines"] = relative
            self.out.send(message)


def _free_tool_id(mon) -> int:  # pragma: no cover
    """A `sys.monitoring` tool that nothing else is using.

    The interpreter has six, and a project's own tests may be running under
    `coverage.py`, which holds the one named for it, or under another kalku:
    taking a free one is what lets both measure."""
    for tool in (3, 4, mon.COVERAGE_ID, mon.PROFILER_ID):
        try:
            mon.use_tool_id(tool, "kalku")
        except ValueError:
            continue
        return tool
    raise RuntimeError("every sys.monitoring tool the interpreter offers is already in use")


def _text(report) -> str:  # pragma: no cover
    """What went wrong: the line pytest picks out of the traceback, which says
    so whole, or the end of the traceback when it picked none. Cutting a
    traceback from its end alone leaves a first line that begins in the middle
    of one."""
    crash = getattr(getattr(report, "longrepr", None), "reprcrash", None)
    headline = getattr(crash, "message", "") or ""
    if headline:
        return headline[:_MESSAGE_LIMIT]
    text = getattr(report, "longreprtext", "") or str(getattr(report, "longrepr", ""))
    return text[-_MESSAGE_LIMIT:]


def monotonic_ms() -> int:
    return int(time.monotonic() * 1000)
