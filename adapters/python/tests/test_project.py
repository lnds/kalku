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
