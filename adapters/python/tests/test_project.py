import os
from pathlib import Path

import pytest

from kalku_python import project


@pytest.mark.parametrize(
    ("addopts", "kept"),
    [
        ("-m 'not stress'", "-m 'not stress'"),
        ("-n auto -ra --cov=pkg --cov-report=term -x", "-ra -x"),
        ("-ra -n4 --dist loadfile -p xdist -p no:randomly", "-ra -p no:randomly"),
        ("--numprocesses=4 --maxprocesses 8 --dist=load", ""),
        ("--import-mode=importlib -nauto --no-cov -q", "--import-mode=importlib -q"),
        ("--cov-config=.coveragerc --cov-fail-under=90 -v", "-v"),
        ("-pxdist -p cov -p no:cacheprovider", "-p no:cacheprovider"),
        ("", ""),
        (None, ""),
        ("unbalanced 'quote", ""),
    ],
)
def test_addopts_lose_only_what_this_kalku_cannot_honour(addopts, kept):
    assert project.sanitized_addopts(addopts) == kept


def write(root: Path, name: str, text: str) -> None:
    (root / name).write_text(text, encoding="utf-8")


def test_addopts_are_read_where_pytest_reads_them(tmp_path):
    assert project.read_addopts(tmp_path) is None
    write(tmp_path, "setup.cfg", "[tool:pytest]\naddopts = -ra\n")
    assert project.read_addopts(tmp_path) == "-ra"
    write(tmp_path, "tox.ini", "[pytest]\naddopts = -rb\n")
    assert project.read_addopts(tmp_path) == "-rb"
    write(tmp_path, "pyproject.toml", '[tool.pytest.ini_options]\naddopts = "-rc"\n')
    assert project.read_addopts(tmp_path) == "-rc"
    write(tmp_path, "pytest.ini", "[pytest]\naddopts = -rd\n")
    assert project.read_addopts(tmp_path) == "-rd"


def test_addopts_given_as_a_list_are_joined(tmp_path):
    write(tmp_path, "pyproject.toml", '[tool.pytest.ini_options]\naddopts = ["-ra", "-q"]\n')
    assert project.read_addopts(tmp_path) == "-ra -q"


def test_a_pyproject_without_pytest_or_with_garbage_has_no_addopts(tmp_path):
    write(tmp_path, "pyproject.toml", "[project]\nname = 'x'\n")
    assert project.read_addopts(tmp_path) is None
    write(tmp_path, "pyproject.toml", "this is not toml [")
    assert project.read_addopts(tmp_path) is None


@pytest.mark.parametrize(
    ("path", "is_test"),
    [
        ("tests/test_a.py", True),
        ("test/helpers.py", True),
        ("pkg/test_a.py", True),
        ("pkg/a_test.py", True),
        ("conftest.py", True),
        ("pkg/conftest.py", True),
        ("pkg/a.py", False),
        ("pkg/testing_utils.py", False),
        ("pkg/contest.py", False),
        ("src/pkg/latest.py", False),
    ],
)
def test_the_suite_is_told_by_its_directory_or_its_name(path, is_test):
    assert project.is_test_file(path) is is_test


def test_what_a_build_or_an_environment_leaves_is_ignored():
    for name in [
        ".git",
        ".venv",
        "venv",
        "__pycache__",
        ".tox",
        "node_modules",
        "pkg.egg-info",
        "x.pyc",
    ]:
        assert project.is_ignored(name), name
    for name in ["src", "pkg", "tests", "docs", "build_tools.py"]:
        assert not project.is_ignored(name), name


def tree(root: Path) -> dict[str, bytes]:
    return {
        str(p.relative_to(root)): p.read_bytes() for p in sorted(root.rglob("*")) if p.is_file()
    }


def test_a_copy_leaves_out_bytecode_environments_and_the_reni(tmp_path):
    src = tmp_path / "src"
    for path in ["pkg/a.py", "pkg/__pycache__/a.pyc", ".venv/bin/python", ".git/HEAD", "reni/x"]:
        (src / path).parent.mkdir(parents=True, exist_ok=True)
        (src / path).write_text("x")
    dest = tmp_path / "dest"

    project.sync_tree(src, dest, skip=src / "reni")

    assert set(tree(dest)) == {"pkg/a.py"}


def test_a_changed_file_is_written_with_a_time_of_now_whatever_the_original_says(tmp_path):
    src, dest = tmp_path / "src", tmp_path / "dest"
    src.mkdir()
    (src / "a.py").write_text("one")
    project.sync_tree(src, dest)

    (src / "a.py").write_text("two")
    os.utime(src / "a.py", (1000, 1000))
    project.sync_tree(src, dest)

    assert (dest / "a.py").read_text() == "two"
    assert (dest / "a.py").stat().st_mtime > 1_000_000


def test_a_file_that_did_not_change_is_left_alone(tmp_path):
    src, dest = tmp_path / "src", tmp_path / "dest"
    src.mkdir()
    (src / "a.py").write_text("one")
    project.sync_tree(src, dest)
    os.utime(dest / "a.py", (5000, 5000))

    project.sync_tree(src, dest)

    assert (dest / "a.py").stat().st_mtime == 5000


def test_what_the_project_no_longer_has_is_removed_and_kinds_may_swap(tmp_path):
    src, dest = tmp_path / "src", tmp_path / "dest"
    (src / "old").mkdir(parents=True)
    (src / "old/a.py").write_text("x")
    (src / "b.py").write_text("x")
    (src / "k").write_text("file")
    project.sync_tree(src, dest)

    (src / "old/a.py").unlink()
    (src / "old").rmdir()
    (src / "b.py").unlink()
    (src / "k").unlink()
    (src / "k").mkdir()
    (src / "k/inner.py").write_text("in")
    project.sync_tree(src, dest)

    assert set(tree(dest)) == {"k/inner.py"}


def test_a_link_is_copied_and_follows_its_target(tmp_path):
    src, dest = tmp_path / "src", tmp_path / "dest"
    src.mkdir()
    os.symlink("one", src / "l")
    project.sync_tree(src, dest)
    assert os.readlink(dest / "l") == "one"

    (src / "l").unlink()
    os.symlink("two", src / "l")
    project.sync_tree(src, dest)

    assert os.readlink(dest / "l") == "two"


def test_packages_are_the_top_level_names_in_the_project_and_under_src(tmp_path):
    for path in [
        "pkg/__init__.py",
        "src/other/__init__.py",
        "loose.py",
        "tests/test_a.py",
        "docs/conf.py",
        ".hidden/x.py",
    ]:
        (tmp_path / path).parent.mkdir(parents=True, exist_ok=True)
        (tmp_path / path).write_text("x")
    (tmp_path / "data").mkdir()

    names = project.project_packages(tmp_path)

    assert {"pkg", "other", "loose", "tests", "docs"} <= names
    assert ".hidden" not in names and "data" not in names
    assert project.import_roots(tmp_path) == [str(tmp_path / "src"), str(tmp_path)]
    assert project.import_roots(tmp_path / "nowhere") == [str(tmp_path / "nowhere")]


@pytest.mark.parametrize(
    "name",
    [
        ".git", ".hg", ".svn", ".venv", "venv", "env", "__pycache__", ".tox", ".nox",
        ".mypy_cache", ".pytest_cache", ".ruff_cache", ".hypothesis", "node_modules",
        "build", "dist", ".eggs", "site-packages", "pkg.egg-info", "mod.pyc", "mod.pyo",
    ],
)  # fmt: skip
def test_every_name_a_build_or_an_environment_leaves_is_ignored(name):
    assert project.is_ignored(name)


@pytest.mark.parametrize("name", ["tests", "test"])
def test_both_names_of_a_suite_directory_make_its_files_tests(name):
    assert project.is_test_file(f"{name}/helpers.py")
    assert project.is_test_file(f"pkg/{name}/helpers.py")


@pytest.mark.parametrize(
    "option",
    [
        "-n 2", "--numprocesses 2", "--numprocesses=2", "--maxprocesses 3", "--dist loadfile",
        "--dist=loadfile", "--tx popen", "--cov-report term", "--cov-config .coveragerc",
        "--cov-fail-under 5", "--cov-context test", "--cov", "--cov=pkg", "--cov-branch", "--no-cov",
    ],
)  # fmt: skip
def test_each_option_this_kalku_cannot_honour_is_dropped_with_its_value(option):
    assert project.sanitized_addopts(f"-ra {option} -q") == "-ra -q"


def test_an_option_that_takes_a_value_and_is_last_loses_only_itself():
    assert project.sanitized_addopts("-ra -n") == "-ra"
    assert project.sanitized_addopts("-ra --dist") == "-ra"
    assert project.sanitized_addopts("-ra -p") == "-ra -p"
    assert project.sanitized_addopts("-ra -p xdist") == "-ra"
    assert project.sanitized_addopts("-p") == "-p"


def test_a_plugin_that_is_not_dropped_keeps_its_flag_and_its_name():
    assert project.sanitized_addopts("-p no:randomly -p mylib") == "-p no:randomly -p mylib"
    assert project.sanitized_addopts("-pmylib") == "-pmylib"


def test_a_value_is_skipped_only_when_the_option_did_not_carry_it():
    assert project.sanitized_addopts("--dist=load keep") == "keep"
    assert project.sanitized_addopts("--dist load keep") == "keep"


def test_a_copy_keeps_what_a_file_may_do_and_a_file_that_replaces_a_directory_wins(tmp_path):
    src, dest = tmp_path / "src", tmp_path / "dest"
    src.mkdir()
    script = src / "run.py"
    script.write_text("x")
    script.chmod(0o755)
    (dest / "run.py").mkdir(parents=True)
    (dest / "run.py" / "inner").write_text("old")

    project.sync_tree(src, dest)

    assert (dest / "run.py").is_file() and os.access(dest / "run.py", os.X_OK)
    script.write_text("changed")
    script.chmod(0o644)
    project.sync_tree(src, dest)
    assert (dest / "run.py").read_text() == "changed"
    assert not os.access(dest / "run.py", os.X_OK)


def test_a_directory_with_python_files_is_a_package_even_without_an_init(tmp_path):
    (tmp_path / "ns").mkdir()
    (tmp_path / "ns" / "mod.py").write_text("x")
    (tmp_path / "data").mkdir()
    (tmp_path / "data" / "x.txt").write_text("x")
    (tmp_path / "pkg").mkdir()
    (tmp_path / "pkg" / "__init__.py").write_text("")
    (tmp_path / "note.txt").write_text("x")

    names = project.project_packages(tmp_path)

    assert "ns" in names and "pkg" in names
    assert "data" not in names and "note" not in names and "note.txt" not in names


def test_the_src_directory_is_an_import_root_only_when_it_exists(tmp_path):
    assert project.import_roots(tmp_path) == [str(tmp_path)]
    (tmp_path / "src").mkdir()
    assert project.import_roots(tmp_path) == [str(tmp_path / "src"), str(tmp_path)]


def test_a_directory_that_cannot_be_listed_has_no_packages(tmp_path):
    assert project.project_packages(tmp_path / "missing") == set()
