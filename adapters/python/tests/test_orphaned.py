"""A kalku whose run is gone: the input ends with no `shutdown` on it, in the
middle of a request that has minutes left to run."""

import json
import os
import subprocess
import time

import pytest

from conftest import GATE

# While the flag names a file, whatever runs this starts a program, writes
# its own pid and the program's there, and takes longer than anybody waits.
STALL = """
    import os
    import subprocess
    import time
    from pathlib import Path


    def stall():
        flag = Path(os.environ["KALKU_SLOW_FLAG"])
        if flag.exists():
            apart = bool(os.environ.get("KALKU_SLOW_APART"))
            child = subprocess.Popen(["sleep", "600"], start_new_session=apart)
            said = Path(flag.read_text())
            said.with_suffix(".tmp").write_text(f"{os.getpid()} {child.pid}")
            said.with_suffix(".tmp").rename(said)
            time.sleep(600)
"""

# A suite that stalls where its tests are collected, which is what `prepare` does.
SLOW_TO_COLLECT = {
    "gate.py": "def gate(a):\n    return a >= 1\n",
    "stall.py": STALL,
    "tests/test_gate.py": """
        from gate import gate
        from stall import stall

        stall()


        def test_gate():
            assert gate(1)
    """,
}

# A suite that stalls in a test, which is what `baseline` and `cast` run.
SLOW_TO_RUN = {
    "gate.py": "def gate(a):\n    return a >= 1\n",
    "stall.py": STALL,
    "tests/test_gate.py": """
        from gate import gate
        from stall import stall


        def test_gate():
            assert gate(1)
            stall()
    """,
}

TEST = "tests/test_gate.py::test_gate"


def alive(pid: int) -> bool:
    out = subprocess.run(["ps", "-o", "stat=", "-p", str(pid)], capture_output=True, text=True)
    state = out.stdout.strip()
    return bool(state) and not state.startswith("Z")


def gone(pids: list[int], within: float) -> bool:
    deadline = time.monotonic() + within
    while any(alive(p) for p in pids) and time.monotonic() < deadline:
        time.sleep(0.05)
    return not any(alive(p) for p in pids)


@pytest.fixture
def stalled(kalku, make_project, tmp_path, monkeypatch):
    """A kalku, the switch that makes its project stall, and what a stalled
    request started; whatever is left of it is ended with the test."""
    flag, said = tmp_path / "flag", tmp_path / "started.pids"
    monkeypatch.setenv("KALKU_SLOW_FLAG", str(flag))
    started: list[int] = []

    def start(files):
        k = kalku(make_project(files))
        assert k.hello()["type"] == "ready"
        return k

    def stall():
        flag.write_text(str(said))

    def wait() -> list[int]:
        deadline = time.monotonic() + 60
        while not said.exists():
            assert time.monotonic() < deadline, "the request never got to the slow part"
            time.sleep(0.05)
        started.extend(int(p) for p in said.read_text().split())
        return started

    yield start, stall, wait
    for pid in started:
        if alive(pid):
            os.kill(pid, 9)


def ends_with_what_it_started(k, pids: list[int]) -> None:
    k.proc.stdin.close()
    try:
        k.proc.wait(timeout=3)
    except subprocess.TimeoutExpired:
        pytest.fail("the kalku outlived its run")
    assert gone(pids, within=2), "what the kalku started outlived it"


def test_a_prepare_under_way_ends_with_the_run(stalled):
    start, stall, wait = stalled
    k = start(SLOW_TO_COLLECT)
    stall()

    k.send({"type": "prepare"})

    ends_with_what_it_started(k, wait())


def test_a_baseline_under_way_ends_with_the_run(stalled):
    start, stall, wait = stalled
    k = start(SLOW_TO_RUN)
    assert k.ask({"type": "prepare"})["type"] == "prepared"
    stall()

    k.send({"type": "baseline"})

    ends_with_what_it_started(k, wait())
    assert b"baseline_done" not in k.proc.stdout.read()


# A program that takes a session of its own is in no process group the
# kalku made, and is still something the kalku started.
def test_a_program_in_a_session_of_its_own_ends_with_the_run(stalled, monkeypatch):
    monkeypatch.setenv("KALKU_SLOW_APART", "1")
    start, stall, wait = stalled
    k = start(SLOW_TO_RUN)
    assert k.ask({"type": "prepare"})["type"] == "prepared"
    stall()

    k.send({"type": "baseline"})

    ends_with_what_it_started(k, wait())


def test_a_cast_under_way_ends_with_the_run(stalled):
    start, stall, wait = stalled
    k = start(SLOW_TO_RUN)
    assert k.ask({"type": "prepare"})["type"] == "prepared"
    assert k.ask({"type": "baseline"})["status"] == "green"
    site = k.site("gate.py", "compare", ">=", ">=")
    stall()

    k.send({"type": "cast", "wekufe": "w", "site": site, "tests": [TEST]})

    ends_with_what_it_started(k, wait())
    assert b"cast_done" not in k.proc.stdout.read()


def test_input_that_ends_after_a_shutdown_has_every_request_answered(kalku, make_project):
    # A driver that wrote all it had to say and closed: the end of its input
    # is there to be read while the first request is still under way.
    k = kalku(make_project(GATE))
    hello = {
        "type": "hello",
        "protocol": 1,
        "root": str(k.root),
        "reni": str(k.reni),
        "worker": 0,
        "inline_limit_bytes": 1 << 20,
        "env": {},
    }
    asked = [hello, {"type": "prepare"}, {"type": "baseline"}, {"type": "shutdown"}]
    lines = "".join(json.dumps({"id": i, **r}) + "\n" for i, r in enumerate(asked, 1))

    out, _ = k.proc.communicate(lines.encode(), timeout=60)

    said = [json.loads(line)["type"] for line in out.splitlines()]
    assert said == ["ready", "prepared", "baseline_done", "bye"]
