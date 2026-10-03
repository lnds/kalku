"""The parent's side of a run: a real child, forked from this process, running
a real pytest on a real project, and what it says on its pipe."""

import os
import select
import time

import pytest

from kalku_python import project, runner


def events_of(run: runner.Run, timeout=60):
    out = []
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        ready, _, _ = select.select([run.fd], [], [], 1)
        if not ready:
            continue
        got = run.read()
        if got is None:
            return out
        out.extend(got)
    raise AssertionError(f"the child did not finish: {out}")


@pytest.fixture
def work(make_project, tmp_path):
    root = make_project(
        {
            "gate.py": "def gate(a):\n    return a >= 1\n",
            "tests/test_gate.py": (
                "import os, time\nfrom gate import gate\n\n"
                "def test_in():\n    assert gate(1)\n\n"
                "def test_out():\n    assert not gate(0)\n\n"
                "def test_nap():\n    time.sleep(float(os.environ.get('KALKU_NAP', '0')))\n"
            ),
        }
    )
    dest = tmp_path / "work"
    project.sync_tree(root, dest)
    return dest


def run(work, mode, tests=(), mutation=None):
    return runner.spawn(runner.Spec(str(work), mode, list(tests), mutation))


def finish(r):
    status = r.finish()
    assert r.finished
    return status


def test_a_collect_says_which_tests_there_are_and_that_it_is_done(work):
    r = run(work, "collect")
    events = events_of(r)
    assert finish(r) == 0
    ids = [i for e in events if e["e"] == "collected" for i in e["ids"]]
    assert ids == [
        "tests/test_gate.py::test_in",
        "tests/test_gate.py::test_out",
        "tests/test_gate.py::test_nap",
    ]
    done = events[-1]
    assert done["e"] == "done" and done["exit"] == 0


def test_a_baseline_reports_each_test_with_its_outcome_duration_and_lines(work):
    r = run(work, "baseline")
    events = events_of(r)
    finish(r)
    results = {e["id"]: e for e in events if e["e"] == "result"}
    assert {t: e["outcome"] for t, e in results.items()} == {
        "tests/test_gate.py::test_in": "passed",
        "tests/test_gate.py::test_out": "passed",
        "tests/test_gate.py::test_nap": "passed",
    }
    assert results["tests/test_gate.py::test_in"]["lines"]["gate.py"] == [2]
    assert "tests/test_gate.py" not in results["tests/test_gate.py::test_in"]["lines"]
    starts = [e["id"] for e in events if e["e"] == "start"]
    assert starts == list(results)


def test_a_failing_test_is_a_result_and_the_child_still_finishes(work):
    (work / "tests/test_gate.py").write_text("def test_bad():\n    assert 1 == 2, 'nope'\n")
    r = run(work, "baseline")
    events = events_of(r)
    finish(r)
    (failed,) = [e for e in events if e["e"] == "result"]
    assert failed["outcome"] == "failed" and "nope" in failed["message"]
    assert events[-1]["exit"] == 1


def test_a_cast_runs_only_the_tests_it_is_given_and_stops_at_the_first_failure(work):
    mutated = "def gate(a):\n    return a > 1\n"
    r = run(
        work,
        "cast",
        ["tests/test_gate.py::test_in", "tests/test_gate.py::test_out"],
        runner.Mutation("gate.py", mutated),
    )
    events = events_of(r)
    finish(r)
    results = [(e["id"], e["outcome"]) for e in events if e["e"] == "result"]
    assert results == [("tests/test_gate.py::test_in", "failed")]


def test_a_cast_serves_the_mutated_module_from_memory_and_writes_nothing(work):
    before = (work / "gate.py").read_text()
    r = run(
        work,
        "cast",
        ["tests/test_gate.py::test_out"],
        runner.Mutation("gate.py", "def gate(a):\n    return a >= 0\n"),
    )
    events = events_of(r)
    finish(r)
    assert [e["outcome"] for e in events if e["e"] == "result"] == ["failed"]
    assert (work / "gate.py").read_text() == before
    assert not list(work.rglob("__pycache__"))


def test_a_module_that_cannot_be_imported_is_a_collection_error(work):
    r = run(
        work,
        "cast",
        ["tests/test_gate.py::test_in"],
        runner.Mutation("gate.py", "x = undefined_name\n\ndef gate(a):\n    return a >= 1\n"),
    )
    events = events_of(r)
    finish(r)
    assert not [e for e in events if e["e"] == "result"]
    errors = [e for e in events if e["e"] == "collect_error"]
    assert errors and "undefined_name" in errors[0]["message"]
    assert events[-1]["exit"] != 0


def test_what_a_child_imported_from_outside_the_project_is_named_for_the_next_one(work):
    r = run(work, "baseline")
    done = events_of(r)[-1]
    finish(r)
    assert "pytest" in done["warm"]
    assert not any(name.split(".")[0] in {"gate", "tests"} for name in done["warm"])


def test_a_child_that_is_killed_takes_its_whole_group_and_is_reaped(work, monkeypatch):
    monkeypatch.setenv("KALKU_NAP", "60")
    r = run(work, "cast", ["tests/test_gate.py::test_nap"])
    deadline = time.monotonic() + 30
    started = False
    while time.monotonic() < deadline and not started:
        ready, _, _ = select.select([r.fd], [], [], 0.5)
        if ready:
            started = any(e["e"] == "start" for e in (r.read() or []))
    assert started
    r.kill()
    # The pipe closes when the process is gone.
    assert events_of(r, timeout=30) == []
    assert finish(r) is None
    with pytest.raises(ProcessLookupError):
        os.kill(r.pid, 0)


def test_killing_what_is_already_gone_is_not_an_error(work):
    r = run(work, "collect")
    events_of(r)
    finish(r)
    r.kill()
    assert r.finish() is None


def test_the_end_of_the_pipe_is_none_and_partial_lines_wait_for_their_newline(tmp_path):
    read_fd, write_fd = os.pipe()
    r = runner.Run(os.getpid(), read_fd)
    os.write(write_fd, b'{"e":"a"}\n{"e":')
    assert r.read() == [{"e": "a"}]
    os.write(write_fd, b'"b"}\nnot json\n')
    assert r.read() == [{"e": "b"}]
    os.close(write_fd)
    assert r.read() is None
    os.close(read_fd)


def test_a_child_is_reaped_once(work):
    r = run(work, "collect")
    events_of(r)
    assert finish(r) == 0
    # A second `finish` does not wait on a process that is no longer there.
    assert r.finish() is None
