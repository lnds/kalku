"""The sites of every fixture, against a golden file beside it.

A golden is only as good as its review: regenerate with
`UPDATE_GOLDEN=1 pytest tests/test_golden.py`, then read the diff.
"""

import json
import os
from pathlib import Path

from kalku_python import protocol, sites, spell

ROOT = Path(__file__).resolve().parent / "fixtures"


def fixtures():
    return sorted(ROOT.glob("*/*.py"))


def render(path):
    relative = str(path.relative_to(ROOT))
    found = sites.find(
        relative, path.read_text(encoding="utf-8"), spell.CAST, ["logging.*", "log.*", "print"]
    )
    return "".join(
        json.dumps(protocol.site_json(s), separators=(",", ":"), ensure_ascii=False) + "\n"
        for s in found.sites
    )


def test_every_fixture_matches_its_golden():
    found = fixtures()
    assert len(found) >= 8, found
    for path in found:
        actual = render(path)
        golden = path.with_suffix(".sites.ndjson")
        if os.environ.get("UPDATE_GOLDEN"):
            golden.write_text(actual, encoding="utf-8")
        assert actual == golden.read_text(encoding="utf-8"), str(path.relative_to(ROOT))


def test_every_site_round_trips():
    """What a site says is where it is: its span holds exactly its original."""
    for path in fixtures():
        text = path.read_text(encoding="utf-8")
        data = text.encode("utf-8")
        for s in sites.find(str(path), text, spell.CAST).sites:
            assert data[s.start.byte : s.end.byte].decode("utf-8") == s.original, (path.name, s)
            assert s.replacement != s.original
