from pathlib import Path

from kalku_python import dependency, fingerprint

PROJECT = {
    "pyproject.toml": "[project]\nname = 'p'\n",
    "README.md": "# p\n",
    "pkg/__init__.py": "",
    "pkg/core.py": "from pkg import util\n",
    "pkg/util.py": "X = 1\n",
    "pkg/other.py": "Y = 1\n",
    "pkg/data.json": "{}\n",
    "pkg/templates/page.html": "<p>\n",
    "tests/conftest.py": "",
    "tests/data/cases.txt": "1\n",
    "tests/unit/conftest.py": "",
    "tests/unit/test_core.py": "from pkg import core\n",
    "tests/unit/test_other.py": "from pkg import other\n",
    "tests/web/test_page.py": "",
    "tests/web/conftest.py": "",
}

CORE = "tests/unit/test_core.py"
OTHER = "tests/unit/test_other.py"
PAGE = "tests/web/test_page.py"


def make(root: Path, files: dict[str, str]) -> Path:
    for path, text in files.items():
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
    return root


def prints_of(root: Path, words: str = "env") -> fingerprint.Prints:
    return fingerprint.Prints(root, dependency.build(root), words)


def changed(root: Path, path: str, text: str, *files: str) -> list[str]:
    """The test files whose fingerprint changes when `path` is written with `text`."""
    before = prints_of(root)
    was = {f: before.of(f) for f in files}
    target = root / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")
    after = prints_of(root)
    return [f for f in files if after.of(f) != was[f]]


def test_a_file_s_fingerprint_changes_with_what_it_imports_and_not_with_the_rest(tmp_path):
    root = make(tmp_path, PROJECT)
    assert changed(root, "pkg/util.py", "X = 2\n", CORE, OTHER, PAGE) == [CORE]
    assert changed(root, "pkg/other.py", "Y = 2\n", CORE, OTHER, PAGE) == [OTHER]
    assert changed(root, CORE, "from pkg import core\n\n", CORE, OTHER, PAGE) == [CORE]


def test_the_conftests_on_a_file_s_path_are_in_it(tmp_path):
    root = make(tmp_path, PROJECT)
    assert changed(root, "tests/unit/conftest.py", "A = 1\n", CORE, OTHER, PAGE) == [CORE, OTHER]
    assert changed(root, "tests/conftest.py", "A = 1\n", CORE, OTHER, PAGE) == [CORE, OTHER, PAGE]


def test_the_packages_a_test_file_is_in_are_in_it(tmp_path):
    root = make(tmp_path, {**PROJECT, "tests/__init__.py": "", "tests/unit/__init__.py": ""})
    assert changed(root, "tests/unit/__init__.py", "A = 1\n", CORE, PAGE) == [CORE]
    # `tests/conftest.py` is imported as `tests.conftest`, for every file under it.
    assert changed(root, "tests/__init__.py", "A = 1\n", CORE, PAGE) == [CORE, PAGE]


def test_the_data_kept_beside_the_code_is_in_it_and_the_top_level_is_not(tmp_path):
    root = make(tmp_path, PROJECT)
    assert changed(root, "pkg/data.json", "[]\n", CORE, OTHER, PAGE) == [CORE, OTHER]
    assert changed(root, "pkg/templates/page.html", "<b>\n", CORE, OTHER, PAGE) == [CORE, OTHER]
    assert changed(root, "README.md", "# q\n", CORE, OTHER, PAGE) == []


def test_what_configures_the_tools_and_the_suite_s_data_are_in_every_one(tmp_path):
    root = make(tmp_path, PROJECT)
    every = [CORE, OTHER, PAGE]
    assert changed(root, "pyproject.toml", "[project]\nname = 'q'\n", *every) == every
    assert changed(root, "requirements-dev.txt", "pytest\n", *every) == every
    assert changed(root, ".env", "A=1\n", *every) == every
    assert changed(root, "tests/data/cases.txt", "2\n", *every) == every


def test_a_new_module_that_an_import_now_finds_changes_it(tmp_path):
    root = make(tmp_path, {**PROJECT, "pkg/core.py": "from pkg import util, extra\n"})
    assert changed(root, "pkg/extra.py", "", CORE, OTHER) == [CORE]


def test_the_environment_is_in_every_one(tmp_path):
    root = make(tmp_path, PROJECT)
    assert prints_of(root, "a").of(CORE) != prints_of(root, "b").of(CORE)
    words = fingerprint.environment()
    assert "kalku-python" in words and "pytest" in words


def test_two_copies_of_a_project_agree(tmp_path):
    a = make(tmp_path / "a", PROJECT)
    b = make(tmp_path / "b", PROJECT)
    assert prints_of(a).of(CORE) == prints_of(b).of(CORE)


def test_modules_no_closure_explains_are_named_in_it_and_taken_again(tmp_path):
    root = make(tmp_path, PROJECT)
    prints = prints_of(root)
    plain = prints.of(CORE)
    named = prints.of(CORE, ("pkg/other.py",))
    assert fingerprint.unexplained_of(plain) == ()
    assert fingerprint.unexplained_of(named) == ("pkg/other.py",)
    assert named != plain and "pkg/other.py" in prints.covers(CORE, ("pkg/other.py",))
    (root / "pkg/other.py").write_text("Y = 2\n")
    assert prints_of(root).of(CORE, ("pkg/other.py",)) != named
    assert prints_of(root).of(CORE) == plain


def test_a_fingerprint_it_did_not_give_names_nothing_it_can_take():
    assert fingerprint.unexplained_of("abc|not json") is None
    assert fingerprint.unexplained_of('abc|{"a":1}') is None
    assert fingerprint.unexplained_of("abc|[1]") is None


def test_what_a_file_covers_is_its_closure_and_what_its_conftests_import(tmp_path):
    root = make(tmp_path, {**PROJECT, "tests/unit/conftest.py": "from pkg import other\n"})
    covers = prints_of(root).covers(CORE)
    assert {"pkg/core.py", "pkg/util.py", "pkg/other.py", "tests/conftest.py"} <= covers
    assert "tests/web/conftest.py" not in covers
    assert prints_of(root).explained({PAGE}) == {
        PAGE,
        "tests/conftest.py",
        "tests/web/conftest.py",
        "tests/data/cases.txt",
    }
