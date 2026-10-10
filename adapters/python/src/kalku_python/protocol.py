"""The kalku protocol: what is asked, and how it is answered.

A request is decoded strictly: a line that is not exactly what the protocol
says is refused by kind, with the path to the field that is wrong, and
nothing is guessed. What this kalku says is canonical: `type`, then `id`,
then the fields in the order the tables list them, with no whitespace.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any

from . import spell

PROTOCOL = 1

# The ceiling on one line, in bytes. The protocol's own.
MAX_LINE = 4 * 1024 * 1024

# ---- what a kalku is asked -------------------------------------------------


@dataclass
class Hello:
    protocol: int
    root: str
    reni: str
    worker: int
    inline_limit_bytes: int
    env: dict[str, str]


@dataclass
class Sites:
    files: list[str]
    spells: list[str]
    exclude_calls: list[str]


@dataclass
class Cast:
    wekufe: str
    site: dict[str, Any]
    tests: list[str]


@dataclass
class Abort:
    cast: int


@dataclass
class Reload:
    files: list[str]


class Prepare: ...


class Baseline: ...


class Reset: ...


class Delegate: ...


class Shutdown: ...


# ---- when it cannot be read ------------------------------------------------

LINE_TOO_LONG = "line_too_long"
NOT_JSON = "not_json"
NOT_OBJECT = "not_object"
MISSING_TYPE = "missing_type"
UNKNOWN_TYPE = "unknown_type"
MISSING_ID = "missing_id"
BAD_FIELD = "bad_field"


@dataclass
class DecodeError(Exception):
    kind: str
    detail: str
    # The request id, when the line got far enough to have one.
    id: int | None = field(default=None)

    def __str__(self) -> str:  # pragma: no cover - for tracebacks
        return f"{self.kind}: {self.detail}"


_REQUESTS = (
    "hello",
    "prepare",
    "baseline",
    "sites",
    "cast",
    "abort",
    "reset",
    "reload",
    "delegate",
    "shutdown",
)


def _bad(path: str, why: str) -> DecodeError:
    return DecodeError(BAD_FIELD, f"{path}: {why}")


def decode(line: str) -> tuple[int, Any]:
    """One request line, as `(id, request)`; raises `DecodeError`."""
    try:
        value = json.loads(line)
    except ValueError as e:
        raise DecodeError(NOT_JSON, str(e)) from None
    if not isinstance(value, dict):
        raise DecodeError(NOT_OBJECT, "the line is not a JSON object")
    kind = value.get("type")
    if not isinstance(kind, str):
        raise DecodeError(MISSING_TYPE, "no string `type`")
    if kind not in _REQUESTS:
        raise DecodeError(UNKNOWN_TYPE, kind)
    ident = value.get("id")
    if not _is_int(ident):
        raise DecodeError(MISSING_ID, "no integer `id`")
    try:
        return ident, _body(kind, value)
    except DecodeError as e:
        e.id = ident
        raise


def _is_int(v: Any) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def _body(kind: str, o: dict[str, Any]) -> Any:
    if kind == "hello":
        return Hello(
            protocol=_int(o, "protocol", ""),
            root=_string(o, "root", ""),
            reni=_string(o, "reni", ""),
            worker=_int(o, "worker", ""),
            inline_limit_bytes=_int(o, "inline_limit_bytes", ""),
            env=_env(o),
        )
    if kind == "prepare":
        return Prepare()
    if kind == "baseline":
        return Baseline()
    if kind == "sites":
        return Sites(
            files=_strings(o, "files", ""),
            spells=_spells(o),
            exclude_calls=_strings(o, "exclude_calls", ""),
        )
    if kind == "cast":
        site = _required(o, "site", "")
        _check_site(site, "site")
        return Cast(wekufe=_string(o, "wekufe", ""), site=site, tests=_strings(o, "tests", ""))
    if kind == "abort":
        return Abort(cast=_int(o, "cast", ""))
    if kind == "reset":
        return Reset()
    if kind == "reload":
        return Reload(files=_strings(o, "files", ""))
    if kind == "delegate":
        _check_scope(_required(o, "scope", ""))
        return Delegate()
    return Shutdown()


# ---- field readers, each naming its path when it fails ---------------------


def _path(parent: str, key: str) -> str:
    return key if not parent else f"{parent}.{key}"


def _required(o: dict[str, Any], key: str, parent: str) -> Any:
    v = o.get(key)
    if v is None:
        raise _bad(_path(parent, key), "is required")
    return v


def _string(o: dict[str, Any], key: str, parent: str) -> str:
    v = _required(o, key, parent)
    if not isinstance(v, str):
        raise _bad(_path(parent, key), "must be a string")
    return v


def _int(o: dict[str, Any], key: str, parent: str) -> int:
    v = _required(o, key, parent)
    if not _is_int(v):
        raise _bad(_path(parent, key), "must be an integer")
    return v


def _strings(o: dict[str, Any], key: str, parent: str) -> list[str]:
    here = _path(parent, key)
    v = _required(o, key, parent)
    if not isinstance(v, list):
        raise _bad(here, "must be an array")
    for i, item in enumerate(v):
        if not isinstance(item, str):
            raise _bad(f"{here}[{i}]", "must be a string")
    return list(v)


def _env(o: dict[str, Any]) -> dict[str, str]:
    v = _required(o, "env", "")
    if not isinstance(v, dict):
        raise _bad("env", "must be an object")
    for k, item in v.items():
        if not isinstance(item, str):
            raise _bad(f"env.{k}", "must be a string")
    return dict(v)


def _spells(o: dict[str, Any]) -> list[str]:
    names = _strings(o, "spells", "")
    for i, name in enumerate(names):
        if spell.parse(name) is None:
            raise _bad(f"spells[{i}]", f"`{name}` is not a spell")
    return names


def _optional(o: dict[str, Any], key: str, parent: str, ok, expected: str) -> None:
    v = o.get(key)
    if v is None or ok(v):
        return
    raise _bad(_path(parent, key), f"must be {expected}")


def _check_site(site: Any, at: str) -> None:
    """A site as `cast` carries it: every field the table lists, in its shape."""
    if not isinstance(site, dict):
        raise _bad(at, "must be an object")
    _string(site, "site_id", at)
    _string(site, "file", at)
    _optional(site, "enclosing", at, lambda v: isinstance(v, str), "a string")
    _optional(site, "ordinal", at, _is_int, "an integer")
    _check_span(_required(site, "span", at), _path(at, "span"))
    name = _string(site, "spell", at)
    if spell.parse(name) is None:
        raise _bad(_path(at, "spell"), f"`{name}` is not a spell")
    _optional(site, "original", at, lambda v: isinstance(v, str), "a string")
    _string(site, "replacement", at)
    reload = _string(site, "reload", at)
    if reload not in ("module", "dependents"):
        raise _bad(_path(at, "reload"), "must be `module` or `dependents`")


def _check_span(span: Any, at: str) -> None:
    if not isinstance(span, dict):
        raise _bad(at, "must be an object")
    _check_position(_required(span, "start", at), _path(at, "start"))
    end = span.get("end")
    if end is not None:
        _check_position(end, _path(at, "end"))


def _check_position(p: Any, at: str) -> None:
    if not isinstance(p, dict):
        raise _bad(at, "must be an object")
    for key in ("line", "col", "byte"):
        _int(p, key, at)


def _check_scope(scope: Any) -> None:
    """Exactly one of `all`, `since`, `files`."""
    if not isinstance(scope, dict):
        raise _bad("scope", "must be an object")
    keys = [k for k in ("all", "since", "files") if scope.get(k) is not None]
    if keys == ["all"]:
        if scope.get("all") is not True:
            raise _bad("scope.all", "can only be `true`")
    elif keys == ["since"]:
        _string(scope, "since", "scope")
    elif keys == ["files"]:
        _strings(scope, "files", "scope")
    elif not keys:
        raise _bad("scope", "needs one of `all`, `since` or `files`")
    else:
        raise _bad("scope", "takes exactly one of `all`, `since` or `files`")


# ---- what a kalku says -----------------------------------------------------


def _line(kind: str, ident: int, fields: list[tuple[str, Any]]) -> str:
    out: dict[str, Any] = {"type": kind, "id": ident}
    for key, value in fields:
        out[key] = value
    return json.dumps(out, separators=(",", ":"), ensure_ascii=False)


def error(ident: int, code: str, message: str, fatal: bool) -> str:
    return _line("error", ident, [("code", code), ("message", message), ("fatal", fatal)])


def ready(ident: int, adapter: str, runtime: str, capabilities: list[str]) -> str:
    return _line(
        "ready",
        ident,
        [
            ("protocol", PROTOCOL),
            ("language", "python"),
            ("adapter", adapter),
            ("runtime", runtime),
            ("spells", list(spell.CAST)),
            ("capabilities", list(capabilities)),
        ],
    )


def prepared(ident: int, duration_ms: int, modules: int) -> str:
    return _line("prepared", ident, [("duration_ms", duration_ms), ("modules", modules)])


def baseline_done(
    ident: int,
    duration_ms: int,
    tests: list[dict[str, Any]],
    failures: list[dict[str, Any]],
    coverage: tuple[str, Any] | None = None,
    differences: list[str] | None = None,
) -> str:
    """`coverage` is `("inline", entries)` or `("path", path)`; or None.
    `differences` is how the run was unlike the project's own, a sentence each."""
    fields: list[tuple[str, Any]] = [
        ("status", "red" if failures else "green"),
        ("duration_ms", duration_ms),
        ("tests", tests),
    ]
    if coverage is not None:
        how, what = coverage
        fields.append(("coverage" if how == "inline" else "coverage_path", what))
    fields.append(("failures", failures))
    if differences is not None:
        fields.append(("differences", differences))
    return _line("baseline_done", ident, fields)


def cast_done(
    ident: int,
    wekufe: str,
    outcome: str,
    duration_ms: int,
    killed_by: str | None = None,
    message: str | None = None,
) -> str:
    fields: list[tuple[str, Any]] = [("wekufe", wekufe), ("outcome", outcome)]
    if killed_by is not None:
        fields.append(("killed_by", killed_by))
    if message is not None:
        fields.append(("message", message))
    fields.append(("duration_ms", duration_ms))
    fields.append(("dirty", False))
    return _line("cast_done", ident, fields)


def aborted(ident: int, cast: int, restored: bool) -> str:
    return _line("aborted", ident, [("cast", cast), ("restored", restored)])


def bye(ident: int) -> str:
    return _line("bye", ident, [])


def sites_found(ident: int, sites: list[dict[str, Any]], skipped: list[dict[str, Any]]) -> str:
    return _line("sites_found", ident, [("sites", sites), ("skipped", skipped)])


def site_json(s) -> dict[str, Any]:
    """A site as the protocol states it, in the order its table lists it."""
    out: dict[str, Any] = {"site_id": s.site_id, "file": s.file}
    if s.enclosing is not None:
        out["enclosing"] = s.enclosing
    out["ordinal"] = s.ordinal
    out["span"] = {
        "start": {"line": s.start.line, "col": s.start.col, "byte": s.start.byte},
        "end": {"line": s.end.line, "col": s.end.col, "byte": s.end.byte},
    }
    out["spell"] = s.spell
    out["original"] = s.original
    out["replacement"] = s.replacement
    out["reload"] = "module"
    return out
