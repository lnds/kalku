import json
import os
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

SRC = Path(__file__).resolve().parent.parent / "src"
sys.path.insert(0, str(SRC))


class Kalku:
    """A real kalku over a real pipe, the way the kaikai side meets it."""

    def __init__(self, root: Path, reni: Path, worker: int = 0, inline_limit: int = 1 << 20):
        env = dict(os.environ, PYTHONPATH=str(SRC))
        self.proc = subprocess.Popen(
            [sys.executable, "-m", "kalku_python"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
        )
        self.root, self.reni, self.worker, self.inline_limit = root, reni, worker, inline_limit
        self.ids = iter(range(1, 10_000))

    def send(self, request: dict) -> int:
        request = {"id": next(self.ids), **request}
        self.proc.stdin.write((json.dumps(request) + "\n").encode())
        self.proc.stdin.flush()
        return request["id"]

    def read(self) -> dict:
        line = self.proc.stdout.readline()
        assert line, (
            f"the kalku closed its pipe; stderr: {self.proc.stderr.read().decode()[-2000:]}"
        )
        # Every line it says is a protocol message, or it would be banished.
        return json.loads(line)

    def ask(self, request: dict) -> dict:
        self.send(request)
        return self.read()

    def hello(self, protocol: int = 1) -> dict:
        return self.ask(
            {
                "type": "hello",
                "protocol": protocol,
                "root": str(self.root),
                "reni": str(self.reni),
                "worker": self.worker,
                "inline_limit_bytes": self.inline_limit,
                "env": {},
            }
        )

    def sites(self, *files: str, spells=("arm", "compare", "connect", "negate", "literal", "call")):
        return self.ask(
            {"type": "sites", "files": list(files), "spells": list(spells), "exclude_calls": []}
        )

    def site(
        self, file: str, spell: str, original: str, replacement: str | None = None, nth: int = 1
    ):
        found = [
            s
            for s in self.sites(file)["sites"]
            if s["spell"] == spell and s["original"] == original
        ]
        site = dict(found[nth - 1])
        if replacement is not None:
            site["replacement"] = replacement
        return site

    def cast(self, site: dict, tests: list[str], wekufe: str = "w") -> dict:
        return self.ask({"type": "cast", "wekufe": wekufe, "site": site, "tests": tests})

    def leave(self) -> int:
        bye = self.ask({"type": "shutdown"})
        assert bye["type"] == "bye"
        self.proc.stdin.close()
        code = self.proc.wait(timeout=20)
        self.proc.stdout.close()
        self.proc.stderr.close()
        return code

    def kill(self) -> None:
        self.proc.kill()
        self.proc.wait()
        for pipe in (self.proc.stdin, self.proc.stdout, self.proc.stderr):
            pipe.close()


@pytest.fixture
def make_project(tmp_path):
    """A project on disk: `make_project({"gate.py": "..."})`."""

    def make(files: dict[str, str], name: str = "proj") -> Path:
        root = tmp_path / name
        for path, text in files.items():
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(textwrap.dedent(text).lstrip("\n"), encoding="utf-8")
        (root / "pyproject.toml").write_text('[tool.pytest.ini_options]\ntestpaths = ["tests"]\n')
        return root

    return make


@pytest.fixture
def kalku(tmp_path):
    started: list[Kalku] = []

    def start(root: Path, worker: int = 0, **options) -> Kalku:
        k = Kalku(root, tmp_path / "reni", worker, **options)
        started.append(k)
        return k

    yield start
    for k in started:
        if k.proc.poll() is None:
            k.kill()


GATE = {
    "gate.py": """
        LIMIT = 9


        def gate(a):
            return a >= 1 and a < LIMIT


        def label(a, names=("low", "high")):
            if a > 5:
                return names[1]
            return names[0]
    """,
    "tests/test_gate.py": """
        from gate import gate, label


        def test_one_is_inside():
            assert gate(1)


        def test_zero_is_outside():
            assert not gate(0)


        def test_labels():
            assert label(9) == "high"
            assert label(1) == "low"
    """,
}

INSIDE = "tests/test_gate.py::test_one_is_inside"
OUTSIDE = "tests/test_gate.py::test_zero_is_outside"
LABELS = "tests/test_gate.py::test_labels"
