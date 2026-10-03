import json

import pytest

from kalku_python import protocol
from kalku_python.protocol import DecodeError, decode

SITE = {
    "site_id": "s",
    "file": "a.py",
    "ordinal": 1,
    "enclosing": "f",
    "span": {
        "start": {"line": 1, "col": 1, "byte": 0},
        "end": {"line": 1, "col": 2, "byte": 1},
    },
    "spell": "compare",
    "original": ">=",
    "replacement": ">",
    "reload": "module",
}


def refused(line: str) -> DecodeError:
    with pytest.raises(DecodeError) as caught:
        decode(line)
    return caught.value


def cast_with(change) -> str:
    site = json.loads(json.dumps(SITE))
    change(site)
    return json.dumps({"type": "cast", "id": 3, "wekufe": "w", "site": site, "tests": []})


def detail(line: str) -> str:
    e = refused(line)
    assert e.kind == protocol.BAD_FIELD, e
    return e.detail


def test_a_line_that_is_not_a_request_is_refused_by_kind():
    assert refused("nonsense").kind == protocol.NOT_JSON
    assert refused("[1]").kind == protocol.NOT_OBJECT
    assert refused('{"id":1}').kind == protocol.MISSING_TYPE
    assert refused('{"type":5,"id":1}').kind == protocol.MISSING_TYPE
    unknown = refused('{"type":"mutate","id":1}')
    assert (unknown.kind, unknown.detail) == (protocol.UNKNOWN_TYPE, "mutate")
    assert refused('{"type":"reset"}').kind == protocol.MISSING_ID
    assert refused('{"type":"reset","id":"1"}').kind == protocol.MISSING_ID
    assert refused('{"type":"reset","id":1.5}').kind == protocol.MISSING_ID
    assert refused('{"type":"reset","id":true}').kind == protocol.MISSING_ID


def test_a_bad_field_is_refused_with_the_id_and_the_path_to_it():
    e = refused('{"type":"hello","id":7,"protocol":1}')
    assert e.id == 7 and e.detail == "root: is required"
    assert refused('{"type":"reset"}').id is None


def test_every_simple_request_decodes():
    for line, kind in [
        ('{"type":"prepare","id":1}', protocol.Prepare),
        ('{"type":"baseline","id":1}', protocol.Baseline),
        ('{"type":"reset","id":1}', protocol.Reset),
        ('{"type":"shutdown","id":1}', protocol.Shutdown),
    ]:
        ident, request = decode(line)
        assert ident == 1 and isinstance(request, kind)
    _, abort = decode('{"type":"abort","id":1,"cast":4}')
    assert abort == protocol.Abort(cast=4)
    _, reload = decode('{"type":"reload","id":1,"files":["a.py"]}')
    assert reload == protocol.Reload(files=["a.py"])


def test_the_fields_of_a_hello_are_read_in_their_shapes():
    hello = {
        "type": "hello",
        "id": 1,
        "protocol": 1,
        "root": "/r",
        "reni": "/n",
        "worker": 2,
        "inline_limit_bytes": 9,
        "env": {"A": "1"},
    }
    ident, request = decode(json.dumps(hello))
    assert ident == 1
    assert request == protocol.Hello(1, "/r", "/n", 2, 9, {"A": "1"})
    assert detail(json.dumps({**hello, "protocol": "1"})) == "protocol: must be an integer"
    assert detail(json.dumps({**hello, "env": []})) == "env: must be an object"
    assert detail(json.dumps({**hello, "env": {"A": 1}})) == "env.A: must be a string"
    assert detail(json.dumps({**hello, "env": None})) == "env: is required"
    assert detail(json.dumps({**hello, "root": 5})) == "root: must be a string"


def test_a_sites_request_names_the_list_and_the_item_that_is_wrong():
    def sites(files, spells):
        return json.dumps(
            {"type": "sites", "id": 1, "files": files, "spells": spells, "exclude_calls": []}
        )

    assert detail(sites(5, [])) == "files: must be an array"
    assert detail(sites([1], [])) == "files[0]: must be a string"
    assert detail(sites([], ["compare", "nope"])) == "spells[1]: `nope` is not a spell"
    _, request = decode(sites(["a.py"], ["arm", "negate"]))
    assert request == protocol.Sites(["a.py"], ["arm", "negate"], [])


def test_a_cast_without_its_site_says_so():
    assert detail('{"type":"cast","id":1,"wekufe":"w","tests":[]}') == "site: is required"


def test_a_site_is_checked_field_by_field():
    cases = [
        (lambda s: s.update(site_id=1), "site.site_id: must be a string"),
        (lambda s: s.update(file=1), "site.file: must be a string"),
        (lambda s: s.update(enclosing=1), "site.enclosing: must be a string"),
        (lambda s: s.update(ordinal="x"), "site.ordinal: must be an integer"),
        (lambda s: s.pop("span"), "site.span: is required"),
        (lambda s: s.update(span=1), "site.span: must be an object"),
        (lambda s: s.update(span={}), "site.span.start: is required"),
        (lambda s: s["span"].update(start=1), "site.span.start: must be an object"),
        (lambda s: s["span"]["start"].pop("col"), "site.span.start.col: is required"),
        (lambda s: s["span"].update(end="x"), "site.span.end: must be an object"),
        (lambda s: s.update(spell="mutate"), "site.spell: `mutate` is not a spell"),
        (lambda s: s.update(original=1), "site.original: must be a string"),
        (lambda s: s.update(replacement=1), "site.replacement: must be a string"),
        (lambda s: s.update(reload="other"), "site.reload: must be `module` or `dependents`"),
    ]
    for change, expected in cases:
        assert detail(cast_with(change)) == expected


def test_a_site_must_be_an_object():
    line = json.dumps({"type": "cast", "id": 1, "wekufe": "w", "site": 5, "tests": []})
    assert detail(line) == "site: must be an object"


def test_what_a_site_may_leave_out_it_may_leave_out():
    for change in [
        lambda s: s.pop("enclosing"),
        lambda s: s.update(ordinal=None),
        lambda s: s.pop("original"),
        lambda s: s["span"].pop("end"),
        lambda s: s["span"].update(end=None),
        lambda s: s.update(reload="dependents"),
    ]:
        decode(cast_with(change))


def test_a_scope_is_exactly_one_of_all_since_or_files():
    def delegate(scope):
        return '{"type":"delegate","id":1,"scope":' + scope + "}"

    for ok in ['{"all":true}', '{"since":"main"}', '{"files":["a"]}']:
        assert isinstance(decode(delegate(ok))[1], protocol.Delegate)
    for scope, expected in [
        ("1", "scope: must be an object"),
        ("{}", "scope: needs one of `all`, `since` or `files`"),
        ('{"all":true,"since":"x"}', "scope: takes exactly one of `all`, `since` or `files`"),
        ('{"all":false}', "scope.all: can only be `true`"),
        ('{"since":1}', "scope.since: must be a string"),
        ('{"files":"a"}', "scope.files: must be an array"),
    ]:
        assert detail(delegate(scope)) == expected
    assert detail('{"type":"delegate","id":1}') == "scope: is required"


def reply(line: str) -> dict:
    return json.loads(line)


def test_replies_carry_their_type_and_id():
    prepared = reply(protocol.prepared(3, 41, 2))
    assert prepared == {"type": "prepared", "id": 3, "duration_ms": 41, "modules": 2}
    assert reply(protocol.bye(8)) == {"type": "bye", "id": 8}
    err = reply(protocol.error(2, "c", "m", True))
    assert err == {"type": "error", "id": 2, "code": "c", "message": "m", "fatal": True}
    assert reply(protocol.aborted(4, 9, False)) == {
        "type": "aborted",
        "id": 4,
        "cast": 9,
        "restored": False,
    }


def test_every_outcome_of_a_cast_is_said_with_what_it_needs():
    killed = reply(protocol.cast_done(5, "w", "killed", 7, killed_by="t"))
    assert killed == {
        "type": "cast_done",
        "id": 5,
        "wekufe": "w",
        "outcome": "killed",
        "killed_by": "t",
        "duration_ms": 7,
        "dirty": False,
    }
    survived = reply(protocol.cast_done(5, "w", "survived", 7))
    assert "killed_by" not in survived and "message" not in survived
    broken = reply(protocol.cast_done(5, "w", "compile_error", 7, message="m"))
    assert broken["message"] == "m" and broken["outcome"] == "compile_error"


def test_a_baseline_is_green_without_failures_and_red_with_them():
    tests = [{"test": "a::t", "file": "a.py", "duration_ms": 4}]
    green = reply(protocol.baseline_done(3, 10, tests, []))
    assert green["status"] == "green" and green["tests"] == tests
    assert "coverage" not in green and "coverage_path" not in green
    failure = [{"test": "a::t", "message": "boom"}]
    red = reply(protocol.baseline_done(3, 10, tests, failure))
    assert red["status"] == "red" and red["failures"] == failure


def test_a_baseline_carries_its_coverage_inline_or_by_path_and_never_both():
    entries = [{"file": "a.py", "line": 3, "tests": ["a::t"]}]
    inline = reply(protocol.baseline_done(3, 1, [], [], ("inline", entries)))
    assert inline["coverage"] == entries and "coverage_path" not in inline
    by_path = reply(protocol.baseline_done(3, 1, [], [], ("path", "/reni/0.json")))
    assert by_path["coverage_path"] == "/reni/0.json" and "coverage" not in by_path


def test_a_ready_announces_exactly_the_capabilities_it_is_given():
    said = reply(protocol.ready(1, "0", "r", ["cast", "abort", "per_test_coverage"]))
    assert said["capabilities"] == ["cast", "abort", "per_test_coverage"]
    assert reply(protocol.ready(1, "0", "r", []))["capabilities"] == []
    assert said["language"] == "python" and said["protocol"] == protocol.PROTOCOL


def test_a_skipped_file_is_reported_with_its_reason():
    skipped = [{"file": "a.py", "reason": "parse_error", "message": "line 1"}]
    found = reply(protocol.sites_found(4, [], skipped))
    assert found == {"type": "sites_found", "id": 4, "sites": [], "skipped": skipped}


def test_what_it_says_is_not_ascii_escaped():
    assert "é" in protocol.error(1, "c", "é", False)
