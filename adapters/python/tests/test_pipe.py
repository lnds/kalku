"""The kalku as the kaikai side meets it: a real process, a real pipe, and
real projects on disk running a real pytest."""

import json
import textwrap
import time
from pathlib import Path

from conftest import GATE, INSIDE, LABELS, OUTSIDE


def prepared(kalku, make_project, files=GATE, **options):
    root = make_project(files)
    k = kalku(root, **options)
    assert k.hello()["type"] == "ready"
    return k, root


def test_it_greets_and_leaves(kalku, make_project):
    k = kalku(make_project(GATE))

    ready = k.hello()

    assert ready["language"] == "python"
    assert ready["capabilities"] == ["cast", "abort", "per_test_coverage"]
    assert "pytest" in ready["runtime"]
    assert k.leave() == 0


def test_a_protocol_it_does_not_speak_is_refused_by_name(kalku, make_project):
    k = kalku(make_project(GATE))

    refused = k.hello(protocol=2)

    assert refused["code"] == "protocol_mismatch" and refused["fatal"] is True


def test_a_garbled_line_is_answered_and_the_conversation_carries_on(kalku, make_project):
    k, _ = prepared(kalku, make_project)
    k.proc.stdin.write(b"not json at all\n")
    k.proc.stdin.flush()

    assert k.read()["code"] == "bad_request"
    assert k.sites("gate.py")["type"] == "sites_found"


def test_nothing_but_hello_works_before_hello(kalku, make_project):
    k = kalku(make_project(GATE))

    said = k.ask({"type": "prepare"})

    assert said["code"] == "not_ready" and said["fatal"] is False


def test_sites_are_found_and_files_that_cannot_be_searched_say_why(kalku, make_project):
    files = {**GATE, "broken.py": "def f(:\n", "notes.md": "# hi\n"}
    k, _ = prepared(kalku, make_project, files)

    found = k.sites("gate.py", "tests/test_gate.py", "broken.py", "notes.md", "missing.py")

    assert any(s["spell"] == "compare" and s["original"] == ">=" for s in found["sites"])
    assert all(s["file"] == "gate.py" for s in found["sites"])
    reasons = {s["file"]: s["reason"] for s in found["skipped"]}
    assert reasons == {
        "tests/test_gate.py": "test_file",
        "broken.py": "parse_error",
        "notes.md": "not_python",
        "missing.py": "unreadable",
    }


def test_a_baseline_lists_every_test_and_says_which_reached_which_line(kalku, make_project):
    k, _ = prepared(kalku, make_project)
    assert k.ask({"type": "prepare"})["type"] == "prepared"

    done = k.ask({"type": "baseline"})

    assert done["status"] == "green", done
    assert sorted(t["test"] for t in done["tests"]) == sorted([INSIDE, OUTSIDE, LABELS])
    by_line = {(e["file"], e["line"]): e["tests"] for e in done["coverage"]}
    # `gate` runs in the two tests that call it, `label` in the one that does.
    assert sorted(by_line[("gate.py", 5)]) == sorted([INSIDE, OUTSIDE])
    assert by_line[("gate.py", 10)] == [LABELS]
    # What ran at import is credited to the tests that run what it belongs to:
    # the constant to every test of the module, the header of `label`, with its
    # default, to the test that ran its body.
    assert sorted(by_line[("gate.py", 1)]) == sorted([INSIDE, OUTSIDE, LABELS])
    assert by_line[("gate.py", 8)] == [LABELS]
    # The suite is not measured.
    assert all(not f.startswith("tests") for f, _ in by_line)


# The tests of this project pass only where each difference the kalku states is so.
UNLIKE = {
    "gate.py": "LIMIT = 9\n",
    ".git/HEAD": "ref: refs/heads/main\n",
    "build/left.txt": "by a build\n",
    ".venv/pyvenv.cfg": "home = /nowhere\n",
    "tests/test_where.py": """
        import os
        import sys

        import gate


        def test_it_runs_in_a_copy_without_what_the_copy_leaves_out():
            assert os.path.exists("gate.py")
            for left_out in (".git", "build", ".venv"):
                assert not os.path.exists(left_out)


        def test_the_project_is_imported_from_the_copy():
            here = os.path.realpath(os.getcwd())
            assert os.path.dirname(os.path.realpath(gate.__file__)) == here
            assert here in [os.path.realpath(p) for p in sys.path]


        def test_the_plugins_said_to_be_off_are_off(pytestconfig):
            assert not pytestconfig.pluginmanager.hasplugin("cacheprovider")
            assert pytestconfig.pluginmanager.is_blocked("xdist")
            assert pytestconfig.pluginmanager.is_blocked("randomly")


        def test_the_process_is_the_kalkus_own_forked():
            assert "kalku_python.service" in sys.modules
    """,
}


def test_what_a_baseline_says_of_its_run_is_what_a_test_finds(kalku, make_project):
    k, root = prepared(kalku, make_project, UNLIKE)
    assert k.ask({"type": "prepare"})["type"] == "prepared"

    done = k.ask({"type": "baseline"})

    assert done["failures"] == []
    assert len(done["tests"]) == 4
    assert len(done["differences"]) == 4
    copy = done["differences"][0]
    assert str(k.reni / "work" / "0") in copy and str(root) not in copy


def test_a_red_suite_is_reported_red_with_the_message_of_the_failure(kalku, make_project):
    files = {**GATE, "tests/test_gate.py": GATE["tests/test_gate.py"].replace("gate(0)", "gate(5)")}
    k, _ = prepared(kalku, make_project, files)
    k.ask({"type": "prepare"})

    done = k.ask({"type": "baseline"})

    assert done["status"] == "red"
    assert [f["test"] for f in done["failures"]] == [OUTSIDE]
    assert "assert" in done["failures"][0]["message"]


def prepare_and_baseline(k):
    assert k.ask({"type": "prepare"})["type"] == "prepared"
    assert k.ask({"type": "baseline"})["status"] == "green"


def test_a_wekufe_a_test_notices_is_killed_by_that_test(kalku, make_project):
    k, _ = prepared(kalku, make_project)
    prepare_and_baseline(k)

    done = k.cast(k.site("gate.py", "compare", ">=", ">"), [INSIDE, OUTSIDE])

    assert done["outcome"] == "killed" and done["killed_by"] == INSIDE
    assert done["dirty"] is False


def test_a_wekufe_no_test_tells_from_the_original_survives(kalku, make_project):
    k, _ = prepared(kalku, make_project)
    prepare_and_baseline(k)

    done = k.cast(k.site("gate.py", "compare", "<", "<="), [INSIDE, OUTSIDE])

    assert done["outcome"] == "survived", done


def test_a_module_constant_is_cast_through_the_tests_that_import_it(kalku, make_project):
    k, _ = prepared(kalku, make_project)
    prepare_and_baseline(k)

    # `LIMIT = 9` to `10` is felt by nothing: no test sits at the boundary.
    done = k.cast(k.site("gate.py", "literal", "9", "10"), [INSIDE, OUTSIDE, LABELS])

    assert done["outcome"] == "survived"


def test_a_default_argument_is_cast_and_felt(kalku, make_project):
    k, _ = prepared(kalku, make_project)
    prepare_and_baseline(k)

    done = k.cast(k.site("gate.py", "literal", '"high"', '""'), [LABELS])

    assert done["outcome"] == "killed" and done["killed_by"] == LABELS


def test_a_wekufe_that_is_not_python_is_a_compile_error_and_the_kalku_goes_on(kalku, make_project):
    k, _ = prepared(kalku, make_project)
    prepare_and_baseline(k)

    broken = k.cast(k.site("gate.py", "compare", ">=", "=>"), [INSIDE])
    after = k.cast(k.site("gate.py", "compare", ">=", ">"), [INSIDE])

    assert broken["outcome"] == "compile_error" and "SyntaxError" in broken["message"]
    assert after["outcome"] == "killed"


def test_a_wekufe_that_breaks_the_import_is_killed_not_lost(kalku, make_project):
    k, _ = prepared(kalku, make_project)
    prepare_and_baseline(k)

    # `LIMIT = 9` becomes a name nobody defined: the module cannot be imported.
    done = k.cast(k.site("gate.py", "literal", "9", "undefined_name"), [INSIDE])

    assert done["outcome"] == "killed"


def test_the_next_cast_starts_from_the_original_module(kalku, make_project):
    k, root = prepared(kalku, make_project)
    original = (root / "gate.py").read_text()
    prepare_and_baseline(k)

    k.cast(k.site("gate.py", "compare", ">=", ">"), [INSIDE])
    again = k.cast(k.site("gate.py", "compare", "<", "<="), [INSIDE, OUTSIDE])

    # If the first wekufe had stuck, `gate(1)` would still fail here.
    assert again["outcome"] == "survived"
    # And the copy in the reni, like the project, was never written.
    assert (root / "gate.py").read_text() == original


def test_the_users_tree_is_never_touched(kalku, make_project):
    k, root = prepared(kalku, make_project)
    before = {p: p.read_bytes() for p in root.rglob("*") if p.is_file()}
    prepare_and_baseline(k)
    k.cast(k.site("gate.py", "compare", ">=", ">"), [INSIDE])
    k.leave()

    assert {p: p.read_bytes() for p in root.rglob("*") if p.is_file()} == before
    assert not list(root.rglob("__pycache__"))


def test_a_kalku_that_was_only_prepared_can_cast(kalku, make_project):
    # The kaikai side asks one kalku for the baseline and only prepares the rest.
    k, _ = prepared(kalku, make_project)
    k.ask({"type": "prepare"})

    done = k.cast(k.site("gate.py", "compare", ">=", ">"), [INSIDE])

    assert done["outcome"] == "killed"


def test_a_cast_before_prepare_is_not_ready_and_a_stale_site_is_refused(kalku, make_project):
    k, _ = prepared(kalku, make_project)
    site = k.site("gate.py", "compare", ">=", ">")
    assert k.cast(site, [INSIDE])["code"] == "not_ready"
    k.ask({"type": "prepare"})

    assert k.cast(site, ["tests/test_gate.py::nope"])["code"] == "unknown_test"
    stale = dict(site, original="<=")
    refused = k.cast(stale, [INSIDE])
    assert refused["code"] == "bad_request" and refused["fatal"] is False


def test_what_it_does_not_answer_is_said_plainly(kalku, make_project):
    k, _ = prepared(kalku, make_project)

    said = k.ask({"type": "reset"})

    assert said["code"] == "not_implemented" and "reset" in said["message"]


def test_a_project_that_cannot_be_collected_is_a_prepare_failure(kalku, make_project):
    files = {**GATE, "tests/test_gate.py": "import nothing_here_exists\n"}
    k, _ = prepared(kalku, make_project, files)

    said = k.ask({"type": "prepare"})

    assert said["code"] == "prepare_failed" and said["fatal"] is True
    assert "nothing_here_exists" in said["message"]


SLOW = {
    "gate.py": "def gate(a):\n    return a >= 1\n",
    "tests/test_gate.py": """
        import os
        import time
        from pathlib import Path

        from gate import gate


        def test_slow():
            assert gate(1)
            flag = Path(os.environ.get("KALKU_SLOW_FLAG", "/nonexistent"))
            if flag.exists():
                Path(flag.read_text()).write_text(str(os.getpid()))
                time.sleep(60)
    """,
}


def alive(pid):
    import subprocess

    out = subprocess.run(["ps", "-o", "stat=", "-p", str(pid)], capture_output=True, text=True)
    state = out.stdout.strip()
    return bool(state) and not state.startswith("Z")


def test_an_abort_stops_a_running_cast_and_the_kalku_casts_again(
    kalku, make_project, tmp_path, monkeypatch
):
    flag, pidfile = tmp_path / "flag", tmp_path / "slow.pid"
    monkeypatch.setenv("KALKU_SLOW_FLAG", str(flag))
    k, _ = prepared(kalku, make_project, SLOW)
    prepare_and_baseline(k)
    site = k.site("gate.py", "compare", ">=", ">=")
    flag.write_text(str(pidfile))

    cast = k.send(
        {"type": "cast", "wekufe": "w", "site": site, "tests": ["tests/test_gate.py::test_slow"]}
    )
    deadline = time.monotonic() + 60
    while not pidfile.exists():
        assert time.monotonic() < deadline, "the cast never started"
        time.sleep(0.05)
    pid = int(pidfile.read_text())
    started = time.monotonic()
    k.send({"type": "abort", "cast": cast})

    # The first thing said is the abort's answer: the cast gets none.
    aborted = k.read()
    assert aborted["type"] == "aborted" and aborted["cast"] == cast and aborted["restored"] is True
    assert time.monotonic() - started < 30
    deadline = time.monotonic() + 10
    while alive(pid) and time.monotonic() < deadline:
        time.sleep(0.05)
    assert not alive(pid), "the test the cast started outlived the abort"

    flag.unlink()
    again = k.cast(site, ["tests/test_gate.py::test_slow"])
    assert again["outcome"] == "survived", again


def test_workers_sharing_a_reni_each_keep_their_own_copy(kalku, make_project, tmp_path):
    root = make_project(GATE)
    a, b = kalku(root, worker=0), kalku(root, worker=1)
    a.hello(), b.hello()

    assert a.ask({"type": "prepare"})["type"] == "prepared"
    assert b.ask({"type": "prepare"})["type"] == "prepared"

    assert (tmp_path / "reni/work/0/gate.py").exists()
    assert (tmp_path / "reni/work/1/gate.py").exists()


def test_a_map_too_big_for_a_line_goes_to_a_file_in_the_reni(kalku, make_project, tmp_path):
    k, _ = prepared(kalku, make_project, inline_limit=10)
    k.ask({"type": "prepare"})

    done = k.ask({"type": "baseline"})

    assert "coverage" not in done
    written = json.loads(Path(done["coverage_path"]).read_text())
    assert any(e["file"] == "gate.py" and e["line"] == 5 for e in written)


def test_the_projects_own_addopts_still_select_what_is_run(kalku, make_project):
    suite = textwrap.dedent(GATE["tests/test_gate.py"]).lstrip("\n")
    suite = suite.replace("def test_labels():", "@pytest.mark.stress\ndef test_labels():")
    files = {**GATE, "tests/test_gate.py": suite.replace("from gate", "import pytest\nfrom gate")}
    root = make_project(files)
    (root / "pyproject.toml").write_text(
        '[tool.pytest.ini_options]\ntestpaths = ["tests"]\n'
        'markers = ["stress"]\naddopts = "-m \'not stress\' -n auto --cov=gate"\n'
    )
    k = kalku(root)
    k.hello()
    assert k.ask({"type": "prepare"})["type"] == "prepared"

    done = k.ask({"type": "baseline"})

    # The stress test is deselected by the project's own choice; `-n` and
    # `--cov`, which this kalku cannot honour, did not stop the run.
    assert done["status"] == "green", done
    assert sorted(t["test"] for t in done["tests"]) == sorted([INSIDE, OUTSIDE])


def test_a_constant_a_test_only_reads_is_judged_against_that_test(kalku, make_project):
    files = {
        "config.py": "LIMIT = 9\n",
        "gate.py": "def gate(a):\n    return a >= 1\n",
        "tests/test_config.py": "import config\n\n\ndef test_limit():\n    assert config.LIMIT == 9\n",
        "tests/test_gate.py": "from gate import gate\n\n\ndef test_gate():\n    assert gate(1)\n",
    }
    k, _ = prepared(kalku, make_project, files)
    k.ask({"type": "prepare"})

    baseline = k.ask({"type": "baseline"})

    # No line of `config.py` runs during `test_limit`, which only reads what the
    # module defined when it was imported: it is credited because it imports it.
    by_line = {(e["file"], e["line"]): e["tests"] for e in baseline["coverage"]}
    assert by_line[("config.py", 1)] == ["tests/test_config.py::test_limit"]
    assert ("gate.py", 1) in by_line and "tests/test_config.py::test_limit" not in by_line[
        ("gate.py", 1)
    ]
    done = k.cast(k.site("config.py", "literal", "9", "10"), ["tests/test_config.py::test_limit"])
    assert done["outcome"] == "killed"
