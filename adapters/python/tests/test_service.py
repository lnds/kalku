"""The service in this process, against a child that is only a script.

What a child says is a list of events on a pipe, so every branch of what the
service does with them can be reached without a project or a fork: the real
thing is what `test_pipe.py` does, over a real pipe with a real pytest.
"""

import contextlib
import io
import json
import os
import sys
from pathlib import Path

import pytest

from kalku_python import framing, protocol, runner, service


class Out(io.BytesIO):
    def flush(self):  # pragma: no cover - nothing to flush
        pass


class FakeRun:
    def __init__(self, events, hang=False):
        self.fd, writer = os.pipe()
        os.write(writer, b"".join((json.dumps(e) + "\n").encode() for e in events))
        self.writer = writer if hang else None
        if not hang:
            os.close(writer)
        self.killed = False
        self.closed = False

    def read(self):
        data = os.read(self.fd, 1 << 20)
        if not data:
            return None
        return [json.loads(line) for line in data.splitlines() if line]

    def kill(self):
        self.killed = True

    def finish(self):
        if not self.closed:
            self.closed = True
            os.close(self.fd)
            if self.writer is not None:
                os.close(self.writer)


class Harness:
    def __init__(self, root, reni, monkeypatch, inline_limit=1 << 20, worker=0):
        self.stdin_r, self.stdin_w = os.pipe()
        self.out = Out()
        self.svc = service.Service(self.stdin_r, self.out)
        self.root, self.reni, self.inline_limit, self.worker = root, reni, inline_limit, worker
        self.specs = []
        self.scripts = {}
        self.hang = False
        self.runs = []
        self.next_id = 1
        monkeypatch.setattr(runner, "spawn", self._spawn)

    def _spawn(self, spec):
        self.specs.append(spec)
        run = FakeRun(self.scripts.get(spec.mode, []), hang=self.hang)
        self.runs.append(run)
        return run

    def say(self, **request):
        request = {"id": self.next_id, **request}
        self.next_id += 1
        replies, stop = self.svc.handle(json.dumps(request))
        return (
            [json.loads(r) for r in replies]
            if not stop
            else ([json.loads(r) for r in replies], True)
        )

    def one(self, **request):
        (reply,) = self.say(**request)
        return reply

    def hello(self, **overrides):
        fields = {
            "type": "hello",
            "protocol": 1,
            "root": str(self.root),
            "reni": str(self.reni),
            "worker": self.worker,
            "inline_limit_bytes": self.inline_limit,
            "env": {},
        }
        return self.one(**{**fields, **overrides})

    def feed(self, data: bytes):
        os.write(self.stdin_w, data)

    def close_input(self):
        os.close(self.stdin_w)
        self.stdin_w = None


@pytest.fixture
def harness(tmp_path, monkeypatch, make_project):
    root = make_project(
        {"gate.py": "def gate(a):\n    return a >= 1\n", "tests/test_gate.py": "x = 1\n"}
    )
    made = []

    def build(**options):
        h = Harness(root, tmp_path / "reni", monkeypatch, **options)
        made.append(h)
        return h

    yield build
    for h in made:
        for fd in (h.stdin_r, h.stdin_w):
            if fd is not None:
                with contextlib.suppress(OSError):
                    os.close(fd)


def collected(*ids):
    return [{"e": "collected", "ids": list(ids)}, {"e": "done", "exit": 0, "warm": []}]


T1, T2 = "tests/test_gate.py::test_one", "tests/test_gate.py::test_two"


def ready(h, ids=(T1, T2)):
    h.hello()
    h.scripts["collect"] = collected(*ids)
    assert h.one(type="prepare")["type"] == "prepared"


def result(test, outcome="passed", lines=None, message="", ms=3):
    e = {"e": "result", "id": test, "outcome": outcome, "duration_ms": ms, "message": message}
    if lines is not None:
        e["lines"] = lines
    return e


def site(**changes):
    base = {
        "site_id": "abc",
        "file": "gate.py",
        "ordinal": 1,
        "span": {
            "start": {"line": 2, "col": 14, "byte": 26},
            "end": {"line": 2, "col": 16, "byte": 28},
        },
        "spell": "compare",
        "original": ">=",
        "replacement": ">",
        "reload": "module",
    }
    return {**base, **changes}


# ---- hello and what comes before it -------------------------------------------


def test_it_greets_with_the_runtime_it_runs_in_and_its_capabilities(harness):
    said = harness().hello()
    assert said["type"] == "ready" and said["language"] == "python"
    assert said["capabilities"] == ["cast", "abort", "per_test_coverage"]
    assert said["runtime"].startswith(f"CPython {sys.version.split()[0]}, pytest ")


def test_a_protocol_it_does_not_speak_is_fatal(harness):
    said = harness().hello(protocol=2)
    assert said["code"] == "protocol_mismatch" and said["fatal"] is True
    assert said["message"] == "kalku speaks protocol 1, kaikai side speaks 2"


def test_a_python_older_than_3_12_is_refused_by_name(harness, monkeypatch):
    monkeypatch.setattr(service.sys, "version_info", (3, 11, 9, "final", 0))
    said = harness().hello()
    assert said["code"] == "unsupported_toolchain" and said["fatal"] is True
    assert "3.12" in said["message"]


def test_an_interpreter_without_pytest_is_refused_and_says_which(harness, monkeypatch):
    monkeypatch.setitem(sys.modules, "pytest", None)
    said = harness().hello()
    assert said["code"] == "toolchain_missing" and said["fatal"] is True
    assert sys.executable in said["message"] and "pytest" in said["message"]


def test_nothing_but_hello_abort_and_shutdown_works_before_hello(harness):
    h = harness()
    for request in [
        {"type": "prepare"},
        {"type": "baseline"},
        {"type": "sites", "files": [], "spells": [], "exclude_calls": []},
        {"type": "cast", "wekufe": "w", "site": site(), "tests": []},
        {"type": "reset"},
    ]:
        said = h.one(**request)
        assert said["code"] == "not_ready" and said["fatal"] is False, request
        assert said["message"] == "`hello` has to come first"
    assert h.one(type="abort", cast=3) == {
        "type": "aborted",
        "id": h.next_id - 1,
        "cast": 3,
        "restored": True,
    }
    replies, stop = h.say(type="shutdown")
    assert stop is True and replies[0]["type"] == "bye"


def test_a_garbled_or_oversized_line_is_refused_and_the_service_goes_on(harness, monkeypatch):
    h = harness()
    replies, stop = h.svc.handle("not json")
    assert not stop and json.loads(replies[0])["code"] == "bad_request"
    assert json.loads(replies[0])["message"].startswith("not_json")
    monkeypatch.setattr(protocol, "MAX_LINE", 50)
    replies, _ = h.svc.handle(framing.TOO_LONG)
    said = json.loads(replies[0])
    assert said["code"] == "bad_request" and "50" in said["message"] and said["id"] == 0
    replies, _ = h.svc.handle('{"type":"hello","id":9}')
    assert json.loads(replies[0])["id"] == 9


def test_what_it_does_not_answer_is_fatal_and_names_the_request(harness):
    h = harness()
    h.hello()
    for kind in ("reset", "reload", "delegate"):
        fields = {"reload": {"files": []}, "delegate": {"scope": {"all": True}}}.get(kind, {})
        said = h.one(type=kind, **fields)
        assert said["code"] == "not_implemented" and said["fatal"] is True
        assert f"`{kind}`" in said["message"]


# ---- sites ----------------------------------------------------------------------


def test_sites_are_found_in_the_users_tree_and_unusable_files_say_why(harness):
    h = harness()
    h.hello()
    (h.root / "broken.py").write_text("def f(:\n")
    (h.root / "notes.md").write_text("# n\n")
    (h.root / "latin.py").write_bytes(b"x = '\xe9'\n")

    said = h.one(
        type="sites",
        files=["gate.py", "tests/test_gate.py", "broken.py", "notes.md", "missing.py", "latin.py"],
        spells=["compare"],
        exclude_calls=[],
    )

    assert [(s["spell"], s["original"]) for s in said["sites"]] == [("compare", ">=")]
    assert {s["file"]: s["reason"] for s in said["skipped"]} == {
        "tests/test_gate.py": "test_file",
        "broken.py": "parse_error",
        "notes.md": "not_python",
        "missing.py": "unreadable",
        "latin.py": "unreadable",
    }
    messages = {s["file"]: s["message"] for s in said["skipped"]}
    assert messages["broken.py"].startswith("line 1") and "missing.py" in messages["missing.py"]


# ---- prepare ----------------------------------------------------------------------


def test_prepare_copies_the_project_and_counts_the_test_files_it_collected(harness):
    h = harness(worker=3)
    h.hello()
    h.scripts["collect"] = collected(T1, T2, "tests/test_other.py::test_x")

    said = h.one(type="prepare")

    assert said["type"] == "prepared" and said["modules"] == 2
    assert (h.reni / "work" / "3" / "gate.py").exists()
    assert h.specs[0].mode == "collect" and h.specs[0].work == str(h.reni / "work" / "3")


def test_a_prepare_before_hello_and_a_copy_that_fails_are_said_plainly(harness, monkeypatch):
    h = harness()
    h.hello()

    def fail(*args, **kwargs):
        raise OSError("disk full")

    monkeypatch.setattr(service.project, "sync_tree", fail)
    said = h.one(type="prepare")
    assert said["code"] == "prepare_failed" and said["fatal"] is True
    assert said["message"] == "cannot copy the project: disk full"


def test_a_suite_that_cannot_be_collected_is_a_prepare_failure_with_its_words(harness):
    h = harness()
    h.hello()
    h.scripts["collect"] = [
        {"e": "collect_error", "id": "tests/test_gate.py", "message": "ImportError: no x"}
    ]
    said = h.one(type="prepare")
    assert said["code"] == "prepare_failed" and said["fatal"] is True
    assert said["message"] == "tests/test_gate.py: ImportError: no x"

    h.scripts["collect"] = [{"e": "crashed", "message": "ValueError: boom"}]
    assert h.one(type="prepare")["message"] == "ValueError: boom"

    h.scripts["collect"] = [{"e": "collect_error", "id": "", "message": "bad"}]
    assert h.one(type="prepare")["message"] == "collection: bad"


# ---- baseline -----------------------------------------------------------------------


def test_a_baseline_before_prepare_is_not_ready(harness):
    h = harness()
    h.hello()
    said = h.one(type="baseline")
    assert said["code"] == "not_ready" and said["message"] == "`prepare` has to come first"


def test_a_baseline_lists_the_tests_and_says_which_reached_which_line(harness):
    h = harness()
    ready(h)
    h.scripts["baseline"] = [
        result(T1, lines={"gate.py": [1, 2]}, ms=5),
        result(T2, lines={"gate.py": [2]}, ms=7),
        {"e": "done", "exit": 0, "warm": []},
    ]

    said = h.one(type="baseline")

    assert said["status"] == "green" and said["failures"] == []
    assert [(t["test"], t["file"], t["duration_ms"]) for t in said["tests"]] == [
        (T1, "tests/test_gate.py", 5),
        (T2, "tests/test_gate.py", 7),
    ]
    by_line = {(e["file"], e["line"]): e["tests"] for e in said["coverage"]}
    assert by_line[("gate.py", 2)] == [T1, T2]
    # The header of `gate` ran at import, so it goes with whoever ran the body.
    assert by_line[("gate.py", 1)] == [T1, T2]
    assert h.specs[-1].mode == "baseline"


def test_a_baseline_is_red_with_the_message_of_each_failing_test(harness):
    h = harness()
    ready(h)
    h.scripts["baseline"] = [
        result(T1, "failed", message="assert 0"),
        result(T2, "failed"),
        result("tests/test_gate.py::test_skip", "skipped"),
        {"e": "done", "exit": 1, "warm": []},
    ]

    said = h.one(type="baseline")

    assert said["status"] == "red"
    assert said["failures"] == [
        {"test": T1, "message": "assert 0"},
        {"test": T2, "message": "the test failed"},
    ]
    assert len(said["tests"]) == 3


def test_a_baseline_that_crashed_or_could_not_collect_is_a_failure_not_a_green(harness):
    h = harness()
    ready(h)
    h.scripts["baseline"] = [{"e": "crashed", "message": "RuntimeError: x"}]
    said = h.one(type="baseline")
    assert (
        said["code"] == "baseline_failed"
        and said["fatal"] is True
        and said["message"] == "RuntimeError: x"
    )


def test_a_map_too_big_for_a_line_goes_to_a_file_in_the_reni(harness):
    h = harness(inline_limit=10, worker=2)
    ready(h)
    h.scripts["baseline"] = [
        result(T1, lines={"gate.py": [1, 2, 3]}),
        {"e": "done", "exit": 0, "warm": []},
    ]

    said = h.one(type="baseline")

    assert "coverage" not in said
    path = h.reni / "coverage" / "2.json"
    assert said["coverage_path"] == str(path)
    assert [e["line"] for e in json.loads(path.read_text())] == [1, 2, 3]


def test_what_a_baseline_imported_from_outside_the_project_is_ready_for_the_next_child(harness):
    h = harness()
    ready(h)
    sys.modules.pop("colorsys", None)
    h.scripts["baseline"] = [
        result(T1),
        {"e": "done", "exit": 0, "warm": ["colorsys", "not_a_module_anywhere_xyz"]},
    ]

    assert h.one(type="baseline")["type"] == "baseline_done"

    assert "colorsys" in sys.modules
    assert "not_a_module_anywhere_xyz" not in sys.modules


def test_a_baseline_stopped_by_the_end_of_input_says_so(harness):
    h = harness()
    ready(h)
    h.hang = True
    h.close_input()
    h.svc._eof = True
    said = h.one(type="baseline")
    assert said["code"] == "aborted" and said["fatal"] is True
    assert h.runs[-1].killed


# ---- cast ---------------------------------------------------------------------------


def cast(h, tests=(T1,), **changes):
    return h.say(type="cast", wekufe="w", site=site(**changes), tests=list(tests))


def test_a_cast_before_prepare_is_not_ready_and_an_unknown_test_is_refused(harness):
    h = harness()
    h.hello()
    assert cast(h)[0]["code"] == "not_ready"
    ready(h)
    said = cast(h, tests=["tests/test_gate.py::nope"])[0]
    assert said["code"] == "unknown_test" and said["fatal"] is False and "nope" in said["message"]


def test_a_site_that_is_not_the_text_it_was_found_in_is_refused_and_nothing_runs(harness):
    h = harness()
    ready(h)
    before = len(h.specs)
    stale = cast(h, original="<=")[0]
    assert stale["code"] == "bad_request" and stale["fatal"] is False
    assert "not the text" in stale["message"]
    unreadable = cast(h, file="missing.py")[0]
    assert unreadable["code"] == "bad_request" and "cannot read" in unreadable["message"]
    assert len(h.specs) == before


def test_a_site_without_an_original_is_taken_at_its_word(harness):
    h = harness()
    ready(h)
    h.scripts["cast"] = [{"e": "start", "id": T1}, result(T1), {"e": "done", "exit": 0, "warm": []}]
    bare = site()
    del bare["original"]
    assert h.one(type="cast", wekufe="w", site=bare, tests=[T1])["outcome"] == "survived"


def test_a_wekufe_that_is_not_python_is_a_compile_error_and_no_child_is_started(harness):
    h = harness()
    ready(h)
    before = len(h.specs)

    said = cast(h, replacement="=>")[0]

    assert said["outcome"] == "compile_error" and said["message"].startswith("SyntaxError:")
    assert "line" in said["message"] and len(h.specs) == before
    nul = cast(h, replacement="\x00")[0]
    assert nul["outcome"] == "compile_error"


def test_a_failing_test_is_what_kills_in_the_order_the_tests_ran(harness):
    h = harness()
    ready(h)
    h.scripts["cast"] = [
        {"e": "start", "id": T1},
        result(T1, "failed"),
        {"e": "done", "exit": 1, "warm": []},
    ]

    said = cast(h, tests=(T1, T2))[0]

    assert said["outcome"] == "killed" and said["killed_by"] == T1 and said["dirty"] is False
    spec = h.specs[-1]
    assert spec.mode == "cast" and spec.tests == [T1, T2]
    assert spec.mutation.file == "gate.py" and ">" in spec.mutation.source
    assert ">=" not in spec.mutation.source


def test_a_child_that_never_finished_or_a_suite_that_would_not_load_is_a_kill(harness):
    h = harness()
    ready(h)
    # The child died after starting the second test: that is the one it was in.
    h.scripts["cast"] = [{"e": "start", "id": T1}, result(T1), {"e": "start", "id": T2}]
    said = cast(h, tests=(T1, T2))[0]
    assert said["outcome"] == "killed" and said["killed_by"] == T2
    # It died before any test started: the first requested test answers for it.
    h.scripts["cast"] = []
    assert cast(h, tests=(T2, T1))[0]["killed_by"] == T2
    # A module the wekufe broke at import cannot be collected.
    h.scripts["cast"] = [
        {"e": "collect_error", "id": T1, "message": "NameError"},
        {"e": "done", "exit": 2, "warm": []},
    ]
    assert cast(h)[0]["outcome"] == "killed"
    h.scripts["cast"] = [{"e": "crashed", "message": "boom"}]
    assert cast(h)[0]["outcome"] == "killed"


def test_every_requested_test_passing_is_a_survivor_and_none_running_is_an_error(harness):
    h = harness()
    ready(h)
    h.scripts["cast"] = [result(T1), result(T2, "xfailed"), {"e": "done", "exit": 0, "warm": []}]
    assert cast(h, tests=(T1, T2))[0]["outcome"] == "survived"
    # Skipped is not a test that passed: if nothing ran and passed, nothing was judged.
    h.scripts["cast"] = [result(T1, "skipped"), {"e": "done", "exit": 0, "warm": []}]
    said = cast(h)[0]
    assert said["code"] == "unknown_test" and said["fatal"] is False
    h.scripts["cast"] = [{"e": "done", "exit": 5, "warm": []}]
    assert cast(h)[0]["code"] == "unknown_test"


def test_an_abort_that_arrives_before_the_cast_began_touches_nothing(harness):
    h = harness()
    ready(h)
    before = len(h.specs)
    h.svc._aborted.add(h.next_id)

    assert cast(h) == []
    assert len(h.specs) == before


def test_an_abort_that_arrives_while_the_child_runs_kills_it_and_the_cast_says_nothing(harness):
    h = harness()
    ready(h)
    h.hang = True
    h.scripts["cast"] = [{"e": "start", "id": T1}]
    cast_id = h.next_id
    h.feed((json.dumps({"type": "abort", "id": 99, "cast": cast_id}) + "\n").encode())

    assert cast(h) == []

    assert h.runs[-1].killed
    # The abort itself is served next, from the queue, and answers.
    line = h.svc.next_line()
    replies, _ = h.svc.handle(line)
    assert json.loads(replies[0]) == {
        "type": "aborted",
        "id": 99,
        "cast": cast_id,
        "restored": True,
    }


def test_an_abort_for_another_cast_does_not_stop_this_one(harness):
    h = harness()
    ready(h)
    h.hang = False
    h.scripts["cast"] = [result(T1), {"e": "done", "exit": 0, "warm": []}]
    h.feed((json.dumps({"type": "abort", "id": 99, "cast": 12345}) + "\n").encode())

    assert cast(h)[0]["outcome"] == "survived"
    assert not h.runs[-1].killed


# ---- the loop -----------------------------------------------------------------------


def test_the_loop_serves_requests_in_order_until_shutdown(harness):
    h = harness()
    lines = [
        {
            "type": "hello",
            "id": 1,
            "protocol": 1,
            "root": str(h.root),
            "reni": str(h.reni),
            "worker": 0,
            "inline_limit_bytes": 1000,
            "env": {},
        },
        {"type": "shutdown", "id": 2},
        {
            "type": "hello",
            "id": 3,
            "protocol": 1,
            "root": "/",
            "reni": "/",
            "worker": 0,
            "inline_limit_bytes": 1,
            "env": {},
        },
    ]
    h.feed(b"".join((json.dumps(line) + "\n").encode() for line in lines))

    assert h.svc.serve() == 0

    said = [json.loads(line) for line in h.out.getvalue().splitlines()]
    assert [s["type"] for s in said] == ["ready", "bye"]


def test_the_loop_ends_at_the_end_of_input_and_drops_a_line_that_never_ends(harness, monkeypatch):
    h = harness()
    monkeypatch.setattr(protocol, "MAX_LINE", 64)
    h.feed(b"x" * 200)
    h.feed(b"\n")
    h.feed(b'{"type":"shutdown","id":5}\n')
    h.close_input()

    assert h.svc.serve() == 0

    said = [json.loads(line) for line in h.out.getvalue().splitlines()]
    assert [s["type"] for s in said] == ["error", "bye"]
    assert said[0]["code"] == "bad_request" and "line_too_long" in said[0]["message"]


def test_the_loop_returns_when_input_closes_with_nothing_said(harness):
    h = harness()
    h.close_input()
    assert h.svc.serve() == 0
    assert h.out.getvalue() == b""


def test_a_line_arriving_in_pieces_is_one_line(harness):
    h = harness()
    h.feed(b'{"type":"shut')
    h.feed(b'down","id":4}\n')
    h.close_input()
    h.svc._feed()
    h.svc._feed()
    assert h.svc.next_line() == '{"type":"shutdown","id":4}'
    assert h.svc.next_line() is None


def test_what_is_said_is_written_with_its_newline_and_flushed(harness):
    h = harness()
    h.svc._say("héllo")
    assert h.out.getvalue() == "héllo\n".encode()


def test_a_conftest_that_imports_a_module_makes_every_test_below_it_depend_on_it(harness):
    h = harness()
    (h.root / "config.py").write_text("LIMIT = 9\n")
    (h.root / "tests/conftest.py").write_text("import config\n")
    (h.root / "tests/sub").mkdir()
    (h.root / "tests/sub/test_x.py").write_text("def test_x():\n    pass\n")
    (h.root / "other").mkdir()
    (h.root / "other/test_y.py").write_text("def test_y():\n    pass\n")
    h.hello()
    h.scripts["collect"] = collected(T1)
    assert h.one(type="prepare")["type"] == "prepared"
    x, y = "tests/sub/test_x.py::test_x", "other/test_y.py::test_y"
    h.scripts["baseline"] = [
        result(x, lines={}),
        result(y, lines={}),
        {"e": "done", "exit": 0, "warm": []},
    ]

    said = h.one(type="baseline")

    by_line = {(e["file"], e["line"]): e["tests"] for e in said["coverage"]}
    assert by_line == {("config.py", 1): [x]}


def test_a_file_that_cannot_be_parsed_adds_no_shared_lines_and_does_not_stop_the_baseline(harness):
    h = harness()
    (h.root / "broken.py").write_text("def f(:\n")
    (h.root / "tests/test_b.py").write_text("import broken\n")
    h.hello()
    h.scripts["collect"] = collected(T1)
    assert h.one(type="prepare")["type"] == "prepared"
    h.scripts["baseline"] = [
        result("tests/test_b.py::t", lines={}),
        {"e": "done", "exit": 0, "warm": []},
    ]

    said = h.one(type="baseline")

    assert said["status"] == "green" and said["coverage"] == []


# ---- the edges of what the service counts, says and refuses ---------------------


class Flushing(io.BytesIO):
    flushes = 0

    def flush(self):
        self.flushes += 1


def reading(tmp_path, data):
    """A service reading from a file, so that one read takes whatever is asked of it."""
    path = tmp_path / "in"
    path.write_bytes(data)
    fd = os.open(path, os.O_RDONLY)
    return service.Service(fd, Out()), fd


def test_the_limits_the_documentation_names_are_these():
    assert service.MINIMUM == (3, 12)
    assert service.MAX_DEPENDENTS == 300
    assert service._WARM_LIMIT == 3000


def test_every_reply_is_flushed_as_it_is_said(harness):
    h = harness()
    h.svc.out = Flushing()
    hello = {"type": "hello", "id": 1, "protocol": 1, "root": str(h.root), "reni": str(h.reni)}
    hello |= {"worker": 0, "inline_limit_bytes": 1, "env": {}}
    lines = [hello, {"type": "shutdown", "id": 2}]
    h.feed(b"".join((json.dumps(line) + "\n").encode() for line in lines))

    assert h.svc.serve() == 0
    assert h.svc.out.flushes == 2


def test_a_read_takes_at_most_a_mebibyte_at_a_time(tmp_path):
    svc, fd = reading(tmp_path, b"x" * (3 << 20))
    svc._feed()
    os.close(fd)
    assert len(svc._buffer) == 1 << 20


def test_a_line_that_never_ends_is_kept_up_to_the_limit_and_dropped_past_it(tmp_path):
    kept, fd = reading(tmp_path, b"x" * protocol.MAX_LINE)
    for _ in range(4):
        kept._feed()
    os.close(fd)
    assert len(kept._buffer) == protocol.MAX_LINE and not kept._dropping

    dropped, fd = reading(tmp_path, b"x" * (protocol.MAX_LINE + 1))
    for _ in range(5):
        dropped._feed()
    os.close(fd)
    assert dropped._buffer == b"" and dropped._dropping


def test_a_line_of_exactly_the_limit_is_kept_and_one_byte_more_is_too_long(harness):
    svc = harness().svc
    assert svc._line(b"x" * protocol.MAX_LINE) is not framing.TOO_LONG
    assert svc._line(b"x" * (protocol.MAX_LINE + 1)) is framing.TOO_LONG


def test_an_abort_is_heard_however_its_type_is_spelled_in_json(harness):
    h = harness()
    h.feed(b'{"type": "ab\\u006frt", "id": 7, "cast": 4}\n')
    h.svc._feed()
    assert h.svc._aborted == {4}


def test_a_line_nobody_can_read_is_refused_without_ending_the_conversation(harness):
    (reply,), stop = harness().svc.handle("not json")
    said = json.loads(reply)
    assert said["code"] == "bad_request" and said["fatal"] is False and not stop


def test_exactly_python_3_12_is_enough_and_the_refusal_names_the_version_it_found(
    harness, monkeypatch
):
    monkeypatch.setattr(service.sys, "version_info", (3, 12, 0, "final", 0))
    assert harness().hello()["type"] == "ready"

    monkeypatch.setattr(service.sys, "version_info", (3, 11, 4, "final", 0))
    monkeypatch.setattr(service.sys, "version", "3.11.4 (main, Jan 1 2024) [GCC]")
    said = harness().hello()
    assert said["message"].startswith("Python 3.11.4 is older than 3.12")


def test_a_duration_is_counted_in_milliseconds(monkeypatch):
    monkeypatch.setattr(service.time, "monotonic", lambda: 12.5)
    assert service._ms(10.0) == 2500


def test_prepare_and_baseline_say_how_long_they_took_in_milliseconds(harness, monkeypatch):
    h = harness()
    h.hello()
    h.scripts["collect"] = collected(T1)
    clock = iter([10.0, 12.5])
    monkeypatch.setattr(service.time, "monotonic", lambda: next(clock))
    assert h.one(type="prepare")["duration_ms"] == 2500

    h.scripts["baseline"] = [result(T1), {"e": "done", "exit": 0, "warm": []}]
    clock = iter([20.0, 23.25])
    monkeypatch.setattr(service.time, "monotonic", lambda: next(clock))
    assert h.one(type="baseline")["duration_ms"] == 3250


def test_a_baseline_before_prepare_is_not_ready_and_not_fatal(harness):
    h = harness()
    h.hello()
    said = h.one(type="baseline")
    assert said["code"] == "not_ready" and said["fatal"] is False
    assert said["message"] == "`prepare` has to come first"


def test_a_cast_before_prepare_says_what_to_do_first_and_is_not_fatal(harness):
    h = harness()
    h.hello()
    said = cast(h)[0]
    assert said["fatal"] is False and said["message"] == "`prepare` has to come first"


def test_a_site_in_a_file_the_reni_cannot_read_is_refused_without_ending_the_run(harness):
    h = harness()
    ready(h)
    said = cast(h, file="missing.py")[0]
    assert said["code"] == "bad_request" and said["fatal"] is False
    assert "missing.py" in said["message"]


def test_a_prepare_stopped_by_the_end_of_input_says_what_stopped_it(harness):
    h = harness()
    h.hello()
    h.scripts["collect"] = collected(T1)
    h.svc._eof = True
    said = h.one(type="prepare")
    assert said["code"] == "aborted" and said["fatal"] is True
    assert said["message"] == "the kalku was asked to stop"


def test_a_baseline_stopped_by_the_end_of_input_says_what_stopped_it(harness):
    h = harness()
    ready(h)
    h.svc._eof = True
    said = h.one(type="baseline")
    assert said["fatal"] is True and said["message"] == "the kalku was asked to stop"


def test_a_child_is_always_closed_once_it_has_been_read(harness):
    h = harness()
    ready(h)
    h.scripts["baseline"] = [result(T1), {"e": "done", "exit": 0, "warm": []}]
    h.one(type="baseline")
    assert all(run.closed for run in h.runs)

    h.hang = True
    h.svc._eof = True
    assert h.svc._run(runner.Spec("w", "baseline")) is None
    assert h.runs[-1].killed and h.runs[-1].closed


def test_the_verdict_names_the_last_test_that_started_when_the_suite_never_finished(harness):
    h = harness()
    ready(h)
    h.scripts["cast"] = [{"e": "start", "id": T1}, {"e": "start", "id": T2}]
    said = cast(h, tests=(T1, T2))[0]
    assert said["outcome"] == "killed" and said["killed_by"] == T2


def test_a_suite_that_never_started_a_test_is_killed_by_the_one_asked_for_or_by_none(harness):
    h = harness()
    ready(h)
    h.scripts["cast"] = [{"e": "crashed", "message": "boom"}]
    assert cast(h, tests=(T2,))[0]["killed_by"] == T2
    assert cast(h, tests=())[0]["killed_by"] == "?"


def test_a_cast_where_no_requested_test_passed_is_an_error_that_says_so(harness):
    h = harness()
    ready(h)
    h.scripts["cast"] = [{"e": "done", "exit": 0, "warm": []}]
    said = cast(h)[0]
    assert said["code"] == "unknown_test" and said["fatal"] is False
    assert said["message"] == "none of the requested tests ran and passed"


def test_a_syntax_error_is_told_with_its_line_and_anything_else_with_its_kind():
    assert service._syntax(SyntaxError("bad", ("f.py", 3, 1, "x", 3, 2))) == (
        "SyntaxError: bad (line 3)"
    )
    assert service._syntax(ValueError("null bytes")) == "ValueError: null bytes"


def spoken(harness, tmp_path, limit):
    h = harness(inline_limit=limit)
    h.reni = tmp_path / "not" / "yet" / "reni"
    h.hello()
    return h


def test_coverage_is_inline_up_to_the_limit_and_in_a_file_of_the_reni_past_it(harness, tmp_path):
    entries = [{"file": "a.py", "line": 1, "tests": [T1]}]
    text = json.dumps(entries, separators=(",", ":"))
    size = len(text.encode())

    assert spoken(harness, tmp_path, size).svc._spoken(entries) == ("inline", entries)

    h = spoken(harness, tmp_path, size - 1)
    kind, path = h.svc._spoken(entries)
    assert kind == "path" and path == str(h.reni / "coverage" / "0.json")
    assert Path(path).read_text() == text + "\n"
    assert h.svc._spoken(entries) == ("path", path)


def attributed(tmp_path, files, monkeypatch, limit):
    for name, text in files.items():
        path = tmp_path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    monkeypatch.setattr(service, "MAX_DEPENDENTS", limit)
    ids = [f"{n}::t" for n in files if n.startswith("tests/")]
    tests = [{"test": i, "file": i.partition("::")[0], "duration_ms": 1} for i in ids]
    svc = service.Service(0, Out())
    return svc._attributed(tmp_path, tests, {i: {} for i in ids})


IMPORTERS = {
    "lib.py": "LIMIT = 1\n",
    "mid.py": "import lib\n",
    "tests/test_direct.py": "import lib\n",
    "tests/test_a.py": "import mid\n",
    "tests/test_b.py": "import mid\n",
}


def test_a_module_is_credited_to_every_test_that_depends_on_it_up_to_the_limit(
    tmp_path, monkeypatch
):
    got = attributed(tmp_path, IMPORTERS, monkeypatch, limit=3)
    for test in ("tests/test_direct.py::t", "tests/test_a.py::t", "tests/test_b.py::t"):
        assert got[test]["lib.py"] == {1}


def test_past_the_limit_a_module_is_credited_only_to_the_tests_that_import_it_themselves(
    tmp_path, monkeypatch
):
    got = attributed(tmp_path, IMPORTERS, monkeypatch, limit=2)
    assert got["tests/test_direct.py::t"]["lib.py"] == {1}
    assert "lib.py" not in got["tests/test_a.py::t"]
    assert "lib.py" not in got["tests/test_b.py::t"]


def test_past_the_limit_even_for_the_direct_importers_a_module_is_credited_to_none(
    tmp_path, monkeypatch
):
    files = {**IMPORTERS, "tests/test_c.py": "import lib\n"}
    got = attributed(tmp_path, files, monkeypatch, limit=1)
    assert all("lib.py" not in lines for lines in got.values())


def test_only_the_first_few_thousand_modules_a_baseline_imported_are_imported_again(monkeypatch):
    seen = []
    monkeypatch.setattr(service, "__import__", seen.append, raising=False)
    names = [f"kalku_no_such_module_{i}" for i in range(service._WARM_LIMIT + 5)]
    service.Service(0, Out())._warm([{"e": "done", "warm": names}])
    assert seen == names[: service._WARM_LIMIT]


def test_a_python_of_exactly_the_minimum_is_enough(harness, monkeypatch):
    monkeypatch.setattr(service.sys, "version_info", service.MINIMUM)
    assert harness().hello()["type"] == "ready"


def test_a_suite_that_cannot_be_collected_ends_the_run(harness):
    h = harness()
    h.hello()
    h.scripts["collect"] = [{"e": "collect_error", "id": "tests/test_gate.py", "message": "boom"}]
    said = h.one(type="prepare")
    assert said["code"] == "prepare_failed" and said["fatal"] is True
    assert said["message"] == "tests/test_gate.py: boom"


def test_a_project_that_cannot_be_copied_ends_the_run(harness, monkeypatch):
    def refuse(*args, **kwargs):
        raise OSError("disk full")

    monkeypatch.setattr(service.project, "sync_tree", refuse)
    h = harness()
    h.hello()
    said = h.one(type="prepare")
    assert said["code"] == "prepare_failed" and said["fatal"] is True
    assert "disk full" in said["message"]


def test_the_direct_importers_are_credited_when_they_are_exactly_the_limit(tmp_path, monkeypatch):
    got = attributed(tmp_path, IMPORTERS, monkeypatch, limit=1)
    assert got["tests/test_direct.py::t"]["lib.py"] == {1}
    assert "lib.py" not in got["tests/test_a.py::t"]
