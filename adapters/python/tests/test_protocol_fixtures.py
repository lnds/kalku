"""This kalku against the protocol's own fixtures: the same lines the kaikai
side, the Elixir kalku and the Rust kalku are held to."""

import io
import json
import os
from pathlib import Path

import pytest

from kalku_python import framing, protocol

FIXTURES = Path(__file__).resolve().parents[3] / "docs" / "protocol" / "fixtures"

# The fixtures belong to the repository, outside this project. Measured on a
# copy, they are not there: that is a skip, except on CI, where a missing
# fixture is a failure.
if not FIXTURES.is_dir():
    if os.environ.get("CI"):
        raise RuntimeError(f"the protocol fixtures are missing: {FIXTURES}")
    pytest.skip("the protocol fixtures are not next to this project", allow_module_level=True)


def lines(path):
    return path.read_text(encoding="utf-8").splitlines()


def test_every_request_fixture_decodes():
    seen = 0
    for path in sorted((FIXTURES / "kalku" / "requests").iterdir()):
        for line in lines(path):
            seen += 1
            protocol.decode(line)
    assert seen >= 10


def test_every_invalid_request_is_refused_with_its_kind():
    for entry in lines(FIXTURES / "invalid" / "kalku_requests.ndjson"):
        v = json.loads(entry)
        with pytest.raises(protocol.DecodeError) as caught:
            protocol.decode(v["line"])
        assert caught.value.kind == v["expect"], v["line"]


def test_framing_matches_its_fixtures():
    for entry in lines(FIXTURES / "invalid" / "framing.ndjson"):
        v = json.loads(entry)
        stream = io.BytesIO(v["input"].encode("utf-8"))
        got = []
        while (line := framing.read_line(stream, v["max"])) is not None:
            got.append(line)
        assert any(g is framing.TOO_LONG for g in got) == (v["expect"] == "line_too_long"), entry
        # The last line is always readable, whatever came before it.
        assert isinstance(got[-1], str) and '"id":8' in got[-1], entry


def test_what_it_says_is_the_fixtures_byte_for_byte():
    said = lambda name: lines(FIXTURES / "kalku" / "replies" / f"{name}.ndjson")  # noqa: E731
    assert protocol.error(
        1, "protocol_mismatch", "kalku speaks protocol 2, kaikai side speaks 1", True
    ) in said("error")
    assert protocol.error(
        5, "unknown_test", "no test test/my_app/parser_test.exs:99", False
    ) in said("error")
    assert protocol.bye(7) in said("bye")
    assert protocol.sites_found(4, [], []) in said("sites_found")


def test_a_ready_reply_is_in_canonical_order():
    said = protocol.ready(1, "0.4.0", "CPython 3.13", ["cast", "abort"])
    v = json.loads(said)
    assert list(v) == [
        "type",
        "id",
        "protocol",
        "language",
        "adapter",
        "runtime",
        "spells",
        "capabilities",
    ]
    assert v["language"] == "python"
    assert said == json.dumps(v, separators=(",", ":"), ensure_ascii=False)
