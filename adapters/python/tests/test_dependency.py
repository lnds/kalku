from pathlib import Path

from kalku_python import dependency


def make(root: Path, files: dict[str, str]) -> Path:
    for path, text in files.items():
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
    return root


def importers(tmp_path, files, target):
    graph = dependency.build(make(tmp_path, files))
    return graph.importers_of(target)


def test_a_file_depends_on_what_it_imports_and_on_what_that_imports(tmp_path):
    files = {
        "pkg/__init__.py": "",
        "pkg/a.py": "X = 1\n",
        "pkg/b.py": "from pkg import a\n",
        "pkg/c.py": "import pkg.b\n",
        "tests/test_c.py": "from pkg.c import thing\n",
        "tests/test_other.py": "import os\n",
    }
    assert importers(tmp_path, files, "pkg/a.py") == {
        "pkg/b.py",
        "pkg/c.py",
        "tests/test_c.py",
    }


def test_importing_a_submodule_runs_the_packages_above_it(tmp_path):
    files = {
        "pkg/__init__.py": "FLAG = 1\n",
        "pkg/sub/__init__.py": "",
        "pkg/sub/mod.py": "",
        "tests/test_a.py": "import pkg.sub.mod\n",
    }
    got = importers(tmp_path, files, "pkg/__init__.py")
    assert "tests/test_a.py" in got
    assert "tests/test_a.py" in importers(tmp_path, files, "pkg/sub/__init__.py")


def test_relative_imports_are_resolved_against_their_package(tmp_path):
    files = {
        "pkg/__init__.py": "",
        "pkg/a.py": "",
        "pkg/b.py": "from . import a\n",
        "pkg/c.py": "from .a import thing\n",
        "pkg/sub/__init__.py": "",
        "pkg/sub/d.py": "from .. import a\nfrom ..a import x\n",
        "pkg/sub/e.py": "from ... import a\n",
    }
    assert importers(tmp_path, files, "pkg/a.py") == {"pkg/b.py", "pkg/c.py", "pkg/sub/d.py"}


def test_the_src_layout_is_a_root_and_a_project_at_the_top_is_another(tmp_path):
    files = {
        "src/lib/__init__.py": "",
        "src/lib/core.py": "",
        "tools.py": "",
        "tests/test_core.py": "from lib import core\nimport tools\n",
    }
    graph = dependency.build(make(tmp_path, files))
    assert graph.importers_of("src/lib/core.py") == {"tests/test_core.py"}
    assert graph.importers_of("tools.py") == {"tests/test_core.py"}


def test_a_test_directory_that_is_not_a_package_finds_its_own_helpers(tmp_path):
    files = {
        "tests/helpers.py": "VALUE = 1\n",
        "tests/test_a.py": "from helpers import VALUE\n",
        "tests/sub/test_b.py": "import helpers\n",
    }
    assert importers(tmp_path, files, "tests/helpers.py") == {
        "tests/test_a.py",
        "tests/sub/test_b.py",
    }


def test_a_name_that_is_not_the_projects_is_not_a_dependency(tmp_path):
    files = {"a.py": "import os, sys, pytest\nfrom collections import abc\n", "b.py": ""}
    graph = dependency.build(make(tmp_path, files))
    assert graph.imports == {"a.py": set(), "b.py": set()}


def test_a_file_that_cannot_be_read_depends_on_nothing_and_a_cycle_ends(tmp_path):
    files = {"a.py": "import b\n", "b.py": "import a\n", "bad.py": "def (:\n", "c.py": "import a\n"}
    (tmp_path / "latin.py").write_bytes(b"x = '\xe9'\n")
    graph = dependency.build(make(tmp_path, files))
    assert graph.imports["bad.py"] == set() and graph.imports["latin.py"] == set()
    # `a` and `b` import each other: each is an importer of the other, and the
    # walk ends rather than going round.
    assert graph.importers_of("a.py") == {"a.py", "b.py", "c.py"}


def test_what_a_build_or_an_environment_leaves_is_not_read(tmp_path):
    files = {
        "a.py": "import b\n",
        "b.py": "",
        ".venv/lib/b.py": "import a\n",
        "node_modules/x/c.py": "import b\n",
    }
    graph = dependency.build(make(tmp_path, files))
    assert set(graph.imports) == {"a.py", "b.py"}
    assert graph.importers_of("b.py") == {"a.py"}


def test_an_import_inside_a_function_or_a_condition_still_counts(tmp_path):
    files = {
        "a.py": "",
        "b.py": "def f():\n    import a\n",
        "c.py": "try:\n    import a\nexcept ImportError:\n    pass\n",
    }
    assert importers(tmp_path, files, "a.py") == {"b.py", "c.py"}
