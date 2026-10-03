"""Honesty check: a wekufe identical to the original must never be killed.

Run it against a real project to see whether anything in its suite or in this
kalku makes a test fail on code nobody changed: a test that depends on order,
on state left by an earlier run, on the working directory, or on a library
that tidies up after a run. Each such failure would be reported as a kill that
no test had earned.

    python tools/neutral_check.py PROJECT FILE [FILE ...] [--sample N]

For every sampled site it casts the site with its own text as the replacement,
against exactly the tests the baseline says reach it, in the same warm kalku.
"""

from __future__ import annotations

import argparse
import json
import random
import subprocess
import sys
import tempfile
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent / "src"


class Kalku:
    def __init__(self, python: str):
        env = {"PYTHONPATH": str(SRC), "PATH": "/usr/bin:/bin"}
        self.proc = subprocess.Popen(
            [python, "-m", "kalku_python"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            env=env,
        )
        self.next_id = 1

    def ask(self, request: dict) -> dict:
        request = {"id": self.next_id, **request}
        self.next_id += 1
        self.proc.stdin.write((json.dumps(request) + "\n").encode())
        self.proc.stdin.flush()
        return json.loads(self.proc.stdout.readline())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("project")
    ap.add_argument("files", nargs="+")
    ap.add_argument("--sample", type=int, default=200)
    ap.add_argument("--python", default=None, help="the project's interpreter")
    args = ap.parse_args()
    root = Path(args.project).resolve()
    python = args.python or str(root / ".venv" / "bin" / "python")
    reni = tempfile.mkdtemp(prefix="kalku-neutral-")

    k = Kalku(python)
    ready = k.ask(
        {
            "type": "hello",
            "protocol": 1,
            "root": str(root),
            "reni": reni,
            "worker": 0,
            "inline_limit_bytes": 1 << 30,
            "env": {},
        }
    )
    print(ready["runtime"])
    assert k.ask({"type": "prepare"})["type"] == "prepared"
    baseline = k.ask({"type": "baseline"})
    print(f"baseline: {baseline['status']}, {len(baseline['tests'])} tests")
    if baseline["status"] != "green":
        print("the suite is red on its own; nothing to check")
        return 2
    covering: dict[tuple[str, int], list[str]] = {}
    for entry in baseline["coverage"]:
        covering[(entry["file"], entry["line"])] = entry["tests"]

    found = k.ask(
        {
            "type": "sites",
            "files": args.files,
            "spells": ["arm", "compare", "connect", "negate", "literal", "call"],
            "exclude_calls": [],
        }
    )
    sites = [s for s in found["sites"] if covering.get((s["file"], s["span"]["start"]["line"]))]
    random.Random(7).shuffle(sites)
    sites = sites[: args.sample]
    print(f"{len(found['sites'])} sites, {len(sites)} covered and sampled")

    wrong = []
    for site in sites:
        neutral = dict(site, replacement=site["original"])
        tests = covering[(site["file"], site["span"]["start"]["line"])]
        done = k.ask({"type": "cast", "wekufe": "n", "site": neutral, "tests": tests})
        if done.get("outcome") != "survived":
            wrong.append((site, done))
    for site, done in wrong:
        where = f"{site['file']}:{site['span']['start']['line']}"
        print(f"NOT NEUTRAL {where} {site['spell']} {site['original']!r}: {done}")
    print(f"{len(sites) - len(wrong)} of {len(sites)} neutral wekufe survived")
    k.ask({"type": "shutdown"})
    return 1 if wrong else 0


if __name__ == "__main__":
    sys.exit(main())
