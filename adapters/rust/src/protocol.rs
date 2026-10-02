//! The kalku protocol: one JSON object per line.
//!
//! Decoding is strict where the protocol is and lenient where it says to be:
//! fields in any order, unknown fields ignored, a missing or `null` optional
//! field absent — and a required field that is missing, of the wrong shape,
//! or outside a closed set an error that names its path. Encoding is
//! canonical: `type`, then `id`, then the fields in the order the tables
//! list them, absent optionals omitted, no whitespace.

use crate::coverage::Entry;
use crate::sites::Site;
use crate::spell::{CAST, Spell};
use serde_json::{Map, Value};

pub const PROTOCOL: i64 = 1;

/// The longest line either side accepts, excluding the newline.
pub const MAX_LINE: usize = 4_194_304;

type Obj = Map<String, Value>;

// ---- what a kalku is asked -----------------------------------------------

#[derive(Debug, Clone, PartialEq)]
pub struct Hello {
    pub protocol: i64,
    pub root: String,
    pub reni: String,
    pub worker: i64,
    pub inline_limit_bytes: i64,
    pub env: Vec<(String, String)>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SitesRequest {
    pub files: Vec<String>,
    pub spells: Vec<Spell>,
    pub exclude_calls: Vec<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Request {
    Hello(Hello),
    Prepare,
    Baseline,
    Sites(SitesRequest),
    Cast {
        wekufe: String,
        site: Value,
        tests: Vec<String>,
    },
    Abort {
        cast: i64,
    },
    Reset,
    Reload {
        files: Vec<String>,
    },
    Delegate,
    Shutdown,
}

// ---- when it cannot be read ----------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorKind {
    LineTooLong,
    NotJson,
    NotObject,
    MissingType,
    UnknownType,
    MissingId,
    BadField,
}

impl ErrorKind {
    pub fn name(self) -> &'static str {
        match self {
            ErrorKind::LineTooLong => "line_too_long",
            ErrorKind::NotJson => "not_json",
            ErrorKind::NotObject => "not_object",
            ErrorKind::MissingType => "missing_type",
            ErrorKind::UnknownType => "unknown_type",
            ErrorKind::MissingId => "missing_id",
            ErrorKind::BadField => "bad_field",
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct DecodeError {
    pub kind: ErrorKind,
    pub detail: String,
    /// The request id, when the line got far enough to have one.
    pub id: Option<i64>,
}

impl DecodeError {
    fn new(kind: ErrorKind, detail: impl Into<String>) -> Self {
        DecodeError {
            kind,
            detail: detail.into(),
            id: None,
        }
    }

    fn with_id(mut self, id: i64) -> Self {
        self.id = Some(id);
        self
    }
}

fn bad(path: &str, why: &str) -> DecodeError {
    DecodeError::new(ErrorKind::BadField, format!("{path}: {why}"))
}

/// Read one request line.
pub fn decode(line: &str) -> Result<(i64, Request), DecodeError> {
    let value: Value = serde_json::from_str(line)
        .map_err(|e| DecodeError::new(ErrorKind::NotJson, e.to_string()))?;
    let Value::Object(object) = value else {
        return Err(DecodeError::new(
            ErrorKind::NotObject,
            "the line is not a JSON object",
        ));
    };

    let kind = match object.get("type") {
        Some(Value::String(t)) => t.as_str(),
        _ => return Err(DecodeError::new(ErrorKind::MissingType, "no string `type`")),
    };
    if !is_request(kind) {
        return Err(DecodeError::new(ErrorKind::UnknownType, kind));
    }
    let id = match object.get("id") {
        Some(Value::Number(n)) if n.is_i64() => n.as_i64().unwrap_or(0),
        _ => return Err(DecodeError::new(ErrorKind::MissingId, "no integer `id`")),
    };

    body(kind, &object)
        .map(|r| (id, r))
        .map_err(|e| e.with_id(id))
}

fn is_request(kind: &str) -> bool {
    matches!(
        kind,
        "hello"
            | "prepare"
            | "baseline"
            | "sites"
            | "cast"
            | "abort"
            | "reset"
            | "reload"
            | "delegate"
            | "shutdown"
    )
}

fn body(kind: &str, o: &Obj) -> Result<Request, DecodeError> {
    Ok(match kind {
        "hello" => Request::Hello(Hello {
            protocol: int(o, "protocol", "")?,
            root: string(o, "root", "")?,
            reni: string(o, "reni", "")?,
            worker: int(o, "worker", "")?,
            inline_limit_bytes: int(o, "inline_limit_bytes", "")?,
            env: env(o)?,
        }),
        "prepare" => Request::Prepare,
        "baseline" => Request::Baseline,
        "sites" => Request::Sites(SitesRequest {
            files: strings(o, "files", "")?,
            spells: spells(o)?,
            exclude_calls: strings(o, "exclude_calls", "")?,
        }),
        "cast" => {
            let site = required(o, "site", "")?;
            check_site(site, "site")?;
            Request::Cast {
                wekufe: string(o, "wekufe", "")?,
                site: site.clone(),
                tests: strings(o, "tests", "")?,
            }
        }
        "abort" => Request::Abort {
            cast: int(o, "cast", "")?,
        },
        "reset" => Request::Reset,
        "reload" => Request::Reload {
            files: strings(o, "files", "")?,
        },
        "delegate" => {
            check_scope(required(o, "scope", "")?)?;
            Request::Delegate
        }
        "shutdown" => Request::Shutdown,
        _ => unreachable!("`is_request` admits no other type"),
    })
}

// ---- field readers, each naming its path when it fails ---------------------

fn path(parent: &str, key: &str) -> String {
    if parent.is_empty() {
        key.to_string()
    } else {
        format!("{parent}.{key}")
    }
}

fn required<'a>(o: &'a Obj, key: &str, parent: &str) -> Result<&'a Value, DecodeError> {
    match o.get(key) {
        None | Some(Value::Null) => Err(bad(&path(parent, key), "is required")),
        Some(v) => Ok(v),
    }
}

fn string(o: &Obj, key: &str, parent: &str) -> Result<String, DecodeError> {
    match required(o, key, parent)? {
        Value::String(s) => Ok(s.clone()),
        _ => Err(bad(&path(parent, key), "must be a string")),
    }
}

fn int(o: &Obj, key: &str, parent: &str) -> Result<i64, DecodeError> {
    match required(o, key, parent)? {
        Value::Number(n) if n.is_i64() => Ok(n.as_i64().unwrap_or(0)),
        _ => Err(bad(&path(parent, key), "must be an integer")),
    }
}

fn strings(o: &Obj, key: &str, parent: &str) -> Result<Vec<String>, DecodeError> {
    let here = path(parent, key);
    match required(o, key, parent)? {
        Value::Array(items) => items
            .iter()
            .enumerate()
            .map(|(i, v)| match v {
                Value::String(s) => Ok(s.clone()),
                _ => Err(bad(&format!("{here}[{i}]"), "must be a string")),
            })
            .collect(),
        _ => Err(bad(&here, "must be an array")),
    }
}

fn env(o: &Obj) -> Result<Vec<(String, String)>, DecodeError> {
    match required(o, "env", "")? {
        Value::Object(m) => m
            .iter()
            .map(|(k, v)| match v {
                Value::String(s) => Ok((k.clone(), s.clone())),
                _ => Err(bad(&format!("env.{k}"), "must be a string")),
            })
            .collect(),
        _ => Err(bad("env", "must be an object")),
    }
}

fn spells(o: &Obj) -> Result<Vec<Spell>, DecodeError> {
    strings(o, "spells", "")?
        .iter()
        .enumerate()
        .map(|(i, name)| {
            Spell::parse(name)
                .ok_or_else(|| bad(&format!("spells[{i}]"), &format!("`{name}` is not a spell")))
        })
        .collect()
}

// A site as `cast` carries it: every field the table lists, in its shape.
fn check_site(site: &Value, at: &str) -> Result<(), DecodeError> {
    let Value::Object(o) = site else {
        return Err(bad(at, "must be an object"));
    };
    string(o, "site_id", at)?;
    string(o, "file", at)?;
    optional(
        o,
        "enclosing",
        at,
        |v| matches!(v, Value::String(_)),
        "a string",
    )?;
    optional(o, "ordinal", at, |v| v.as_i64().is_some(), "an integer")?;
    let span = required(o, "span", at)?;
    check_span(span, &path(at, "span"))?;
    let spell = string(o, "spell", at)?;
    if Spell::parse(&spell).is_none() {
        return Err(bad(
            &path(at, "spell"),
            &format!("`{spell}` is not a spell"),
        ));
    }
    optional(
        o,
        "original",
        at,
        |v| matches!(v, Value::String(_)),
        "a string",
    )?;
    string(o, "replacement", at)?;
    let reload = string(o, "reload", at)?;
    if reload != "module" && reload != "dependents" {
        return Err(bad(&path(at, "reload"), "must be `module` or `dependents`"));
    }
    Ok(())
}

fn check_span(span: &Value, at: &str) -> Result<(), DecodeError> {
    let Value::Object(o) = span else {
        return Err(bad(at, "must be an object"));
    };
    check_position(required(o, "start", at)?, &path(at, "start"))?;
    if let Some(end) = o.get("end").filter(|v| !v.is_null()) {
        check_position(end, &path(at, "end"))?;
    }
    Ok(())
}

fn check_position(p: &Value, at: &str) -> Result<(), DecodeError> {
    let Value::Object(o) = p else {
        return Err(bad(at, "must be an object"));
    };
    for key in ["line", "col", "byte"] {
        int(o, key, at)?;
    }
    Ok(())
}

fn optional(
    o: &Obj,
    key: &str,
    parent: &str,
    ok: impl Fn(&Value) -> bool,
    expected: &str,
) -> Result<(), DecodeError> {
    match o.get(key) {
        None | Some(Value::Null) => Ok(()),
        Some(v) if ok(v) => Ok(()),
        Some(_) => Err(bad(&path(parent, key), &format!("must be {expected}"))),
    }
}

// Exactly one of `all`, `since`, `files`.
fn check_scope(scope: &Value) -> Result<(), DecodeError> {
    let Value::Object(o) = scope else {
        return Err(bad("scope", "must be an object"));
    };
    let keys: Vec<&str> = ["all", "since", "files"]
        .into_iter()
        .filter(|k| o.get(*k).is_some_and(|v| !v.is_null()))
        .collect();
    match keys.as_slice() {
        ["all"] if o.get("all") == Some(&Value::Bool(true)) => Ok(()),
        ["all"] => Err(bad("scope.all", "can only be `true`")),
        ["since"] => string(o, "since", "scope").map(|_| ()),
        ["files"] => strings(o, "files", "scope").map(|_| ()),
        [] => Err(bad("scope", "needs one of `all`, `since` or `files`")),
        _ => Err(bad(
            "scope",
            "takes exactly one of `all`, `since` or `files`",
        )),
    }
}

// ---- what a kalku says ------------------------------------------------------

/// A file that could not be searched, and why.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Skipped {
    pub file: String,
    pub reason: String,
    pub message: String,
}

fn line(kind: &str, id: i64, fields: Vec<(&str, Value)>) -> String {
    let mut m = Map::new();
    m.insert("type".into(), kind.into());
    m.insert("id".into(), id.into());
    for (k, v) in fields {
        m.insert(k.into(), v);
    }
    Value::Object(m).to_string()
}

pub fn error(id: i64, code: &str, message: &str, fatal: bool) -> String {
    line(
        "error",
        id,
        vec![
            ("code", code.into()),
            ("message", message.into()),
            ("fatal", fatal.into()),
        ],
    )
}

pub fn ready(id: i64, adapter: &str, runtime: &str, capabilities: &[&str]) -> String {
    line(
        "ready",
        id,
        vec![
            ("protocol", PROTOCOL.into()),
            ("language", "rust".into()),
            ("adapter", adapter.into()),
            ("runtime", runtime.into()),
            (
                "spells",
                CAST.iter()
                    .map(|s| Value::from(s.name()))
                    .collect::<Vec<_>>()
                    .into(),
            ),
            (
                "capabilities",
                capabilities
                    .iter()
                    .map(|c| Value::from(*c))
                    .collect::<Vec<_>>()
                    .into(),
            ),
        ],
    )
}

pub fn prepared(id: i64, duration_ms: u64, modules: usize) -> String {
    line(
        "prepared",
        id,
        vec![
            ("duration_ms", duration_ms.into()),
            ("modules", (modules as i64).into()),
        ],
    )
}

/// One test of a baseline.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Timed {
    pub test: String,
    pub file: String,
    pub duration_ms: u64,
}

/// One failing test and what it said.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Failure {
    pub test: String,
    pub message: String,
}

fn object(fields: Vec<(&str, Value)>) -> Value {
    Value::Object(
        fields
            .into_iter()
            .map(|(k, v)| (k.to_string(), v))
            .collect(),
    )
}

/// Where a baseline puts what each test reached.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Coverage {
    Inline(Vec<Entry>),
    /// Too much to say on a line: the same entries, one per line, in a file.
    Path(String),
}

pub fn entry_json(e: &Entry) -> Value {
    object(vec![
        ("file", e.file.clone().into()),
        ("line", (e.line as i64).into()),
        (
            "tests",
            e.tests
                .iter()
                .map(|t| Value::from(t.as_str()))
                .collect::<Vec<_>>()
                .into(),
        ),
    ])
}

pub fn baseline_done(
    id: i64,
    duration_ms: u64,
    tests: &[Timed],
    failures: &[Failure],
    coverage: Option<&Coverage>,
) -> String {
    let status = if failures.is_empty() { "green" } else { "red" };
    let tests: Vec<Value> = tests
        .iter()
        .map(|t| {
            object(vec![
                ("test", t.test.clone().into()),
                ("file", t.file.clone().into()),
                ("duration_ms", t.duration_ms.into()),
            ])
        })
        .collect();
    let failures: Vec<Value> = failures
        .iter()
        .map(|f| {
            object(vec![
                ("test", f.test.clone().into()),
                ("message", f.message.clone().into()),
            ])
        })
        .collect();
    let mut fields: Vec<(&str, Value)> = vec![
        ("status", status.into()),
        ("duration_ms", duration_ms.into()),
        ("tests", tests.into()),
    ];
    match coverage {
        Some(Coverage::Inline(entries)) => fields.push((
            "coverage",
            entries.iter().map(entry_json).collect::<Vec<_>>().into(),
        )),
        Some(Coverage::Path(path)) => fields.push(("coverage_path", path.clone().into())),
        None => {}
    }
    fields.push(("failures", failures.into()));
    line("baseline_done", id, fields)
}

/// How one cast ended, as the kalku can know it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    Killed { by: String },
    Survived,
    CompileError { message: String },
}

pub fn cast_done(id: i64, wekufe: &str, outcome: &Outcome, duration_ms: u64) -> String {
    let mut fields: Vec<(&str, Value)> = vec![("wekufe", wekufe.into())];
    match outcome {
        Outcome::Killed { by } => {
            fields.push(("outcome", "killed".into()));
            fields.push(("killed_by", by.clone().into()));
        }
        Outcome::Survived => fields.push(("outcome", "survived".into())),
        Outcome::CompileError { message } => {
            fields.push(("outcome", "compile_error".into()));
            fields.push(("message", message.clone().into()));
        }
    }
    fields.push(("duration_ms", duration_ms.into()));
    fields.push(("dirty", false.into()));
    line("cast_done", id, fields)
}

pub fn aborted(id: i64, cast: i64, restored: bool) -> String {
    line(
        "aborted",
        id,
        vec![("cast", cast.into()), ("restored", restored.into())],
    )
}

pub fn bye(id: i64) -> String {
    line("bye", id, vec![])
}

pub fn sites_found(id: i64, sites: &[Site], skipped: &[Skipped]) -> String {
    line(
        "sites_found",
        id,
        vec![
            (
                "sites",
                sites.iter().map(site_json).collect::<Vec<_>>().into(),
            ),
            (
                "skipped",
                skipped.iter().map(skipped_json).collect::<Vec<_>>().into(),
            ),
        ],
    )
}

pub fn site_json(s: &Site) -> Value {
    let mut m = Map::new();
    m.insert("site_id".into(), s.site_id.clone().into());
    m.insert("file".into(), s.file.clone().into());
    if let Some(e) = &s.enclosing {
        m.insert("enclosing".into(), e.clone().into());
    }
    m.insert("ordinal".into(), (s.ordinal as i64).into());
    let mut span = Map::new();
    span.insert("start".into(), position_json(&s.start));
    span.insert("end".into(), position_json(&s.end));
    m.insert("span".into(), Value::Object(span));
    m.insert("spell".into(), s.spell.name().into());
    m.insert("original".into(), s.original.clone().into());
    m.insert("replacement".into(), s.replacement.clone().into());
    m.insert("reload".into(), "module".into());
    Value::Object(m)
}

fn position_json(p: &crate::source::Position) -> Value {
    let mut m = Map::new();
    m.insert("line".into(), (p.line as i64).into());
    m.insert("col".into(), (p.col as i64).into());
    m.insert("byte".into(), (p.byte as i64).into());
    Value::Object(m)
}

fn skipped_json(s: &Skipped) -> Value {
    let mut m = Map::new();
    m.insert("file".into(), s.file.clone().into());
    m.insert("reason".into(), s.reason.clone().into());
    m.insert("message".into(), s.message.clone().into());
    Value::Object(m)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn refused(line: &str) -> DecodeError {
        decode(line).expect_err(line)
    }

    fn bad_field(line: &str) -> String {
        let e = refused(line);
        assert_eq!(e.kind, ErrorKind::BadField, "{line}: {}", e.detail);
        e.detail
    }

    const SITE: &str = r#"{"site_id":"s","file":"a.rs","ordinal":1,"enclosing":"f",
        "span":{"start":{"line":1,"col":1,"byte":0},"end":{"line":1,"col":2,"byte":1}},
        "spell":"compare","original":">=","replacement":">","reload":"module"}"#;

    fn cast_with(change: impl Fn(&mut Value)) -> String {
        let mut site: Value = serde_json::from_str(SITE).unwrap();
        change(&mut site);
        json!({"type":"cast","id":3,"wekufe":"w","site":site,"tests":[]}).to_string()
    }

    #[test]
    fn every_kind_of_refusal_has_its_wire_name() {
        let names = [
            (ErrorKind::LineTooLong, "line_too_long"),
            (ErrorKind::NotJson, "not_json"),
            (ErrorKind::NotObject, "not_object"),
            (ErrorKind::MissingType, "missing_type"),
            (ErrorKind::UnknownType, "unknown_type"),
            (ErrorKind::MissingId, "missing_id"),
            (ErrorKind::BadField, "bad_field"),
        ];
        for (kind, wire) in names {
            assert_eq!(kind.name(), wire);
        }
    }

    #[test]
    fn a_line_that_is_not_a_request_is_refused_by_kind() {
        assert_eq!(refused("nonsense").kind, ErrorKind::NotJson);
        assert_eq!(refused("[1]").kind, ErrorKind::NotObject);
        assert_eq!(refused(r#"{"id":1}"#).kind, ErrorKind::MissingType);
        assert_eq!(refused(r#"{"type":5,"id":1}"#).kind, ErrorKind::MissingType);
        let unknown = refused(r#"{"type":"mutate","id":1}"#);
        assert_eq!(
            (unknown.kind, unknown.detail.as_str()),
            (ErrorKind::UnknownType, "mutate")
        );
        assert_eq!(refused(r#"{"type":"reset"}"#).kind, ErrorKind::MissingId);
        assert_eq!(
            refused(r#"{"type":"reset","id":"1"}"#).kind,
            ErrorKind::MissingId
        );
        assert_eq!(
            refused(r#"{"type":"reset","id":1.5}"#).kind,
            ErrorKind::MissingId
        );
    }

    #[test]
    fn a_bad_field_is_refused_with_the_id_and_the_path_to_it() {
        let e = refused(r#"{"type":"hello","id":7,"protocol":1}"#);

        assert_eq!(e.id, Some(7));
        assert_eq!(e.detail, "root: is required");
        assert_eq!(refused(r#"{"type":"reset"}"#).id, None);
    }

    #[test]
    fn every_simple_request_decodes() {
        for (line, request) in [
            (r#"{"type":"prepare","id":1}"#, Request::Prepare),
            (r#"{"type":"baseline","id":1}"#, Request::Baseline),
            (r#"{"type":"reset","id":1}"#, Request::Reset),
            (r#"{"type":"shutdown","id":1}"#, Request::Shutdown),
            (
                r#"{"type":"abort","id":1,"cast":4}"#,
                Request::Abort { cast: 4 },
            ),
            (
                r#"{"type":"reload","id":1,"files":["a.rs"]}"#,
                Request::Reload {
                    files: vec!["a.rs".into()],
                },
            ),
        ] {
            assert_eq!(decode(line).unwrap().1, request, "{line}");
        }
    }

    #[test]
    fn the_fields_of_a_hello_are_read_in_their_shapes() {
        let hello = r#"{"type":"hello","id":1,"protocol":1,"root":"/r","reni":"/n",
            "worker":2,"inline_limit_bytes":9,"env":{"A":"1"}}"#;

        let (id, request) = decode(hello).unwrap();

        assert_eq!(id, 1);
        assert_eq!(
            request,
            Request::Hello(Hello {
                protocol: 1,
                root: "/r".into(),
                reni: "/n".into(),
                worker: 2,
                inline_limit_bytes: 9,
                env: vec![("A".into(), "1".into())],
            })
        );
        assert_eq!(
            bad_field(r#"{"type":"hello","id":1,"protocol":"1"}"#),
            "protocol: must be an integer"
        );
        let with = |env: &str| {
            format!(
                r#"{{"type":"hello","id":1,"protocol":1,"root":"/r","reni":"/n","worker":0,"inline_limit_bytes":1,"env":{env}}}"#
            )
        };
        assert_eq!(bad_field(&with("[]")), "env: must be an object");
        assert_eq!(bad_field(&with(r#"{"A":1}"#)), "env.A: must be a string");
        assert_eq!(bad_field(&with("null")), "env: is required");
    }

    #[test]
    fn a_sites_request_names_the_list_and_the_item_that_is_wrong() {
        let with = |files: &str, spells: &str| {
            format!(
                r#"{{"type":"sites","id":1,"files":{files},"spells":{spells},"exclude_calls":[]}}"#
            )
        };

        assert_eq!(bad_field(&with("5", "[]")), "files: must be an array");
        assert_eq!(bad_field(&with("[1]", "[]")), "files[0]: must be a string");
        assert_eq!(
            bad_field(&with("[]", r#"["compare","nope"]"#)),
            "spells[1]: `nope` is not a spell"
        );
        match decode(&with(r#"["a.rs"]"#, r#"["arm","negate"]"#))
            .unwrap()
            .1
        {
            Request::Sites(s) => {
                assert_eq!(s.files, ["a.rs"]);
                assert_eq!(s.spells, [Spell::Arm, Spell::Negate]);
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn a_cast_without_its_site_says_so() {
        assert_eq!(
            bad_field(r#"{"type":"cast","id":1,"wekufe":"w","tests":[]}"#),
            "site: is required"
        );
    }

    #[test]
    fn a_site_is_checked_field_by_field() {
        type Change = Box<dyn Fn(&mut Value)>;
        let cases: [(Change, &str); 14] = [
            (Box::new(|s| *s = json!(5)), "site: must be an object"),
            (
                Box::new(|s| s["site_id"] = json!(1)),
                "site.site_id: must be a string",
            ),
            (
                Box::new(|s| s["enclosing"] = json!(1)),
                "site.enclosing: must be a string",
            ),
            (
                Box::new(|s| s["ordinal"] = json!("x")),
                "site.ordinal: must be an integer",
            ),
            (
                Box::new(|s| {
                    s.as_object_mut().unwrap().remove("span");
                }),
                "site.span: is required",
            ),
            (
                Box::new(|s| s["span"] = json!(1)),
                "site.span: must be an object",
            ),
            (
                Box::new(|s| s["span"] = json!({})),
                "site.span.start: is required",
            ),
            (
                Box::new(|s| s["span"]["start"] = json!(1)),
                "site.span.start: must be an object",
            ),
            (
                Box::new(|s| {
                    s["span"]["start"].as_object_mut().unwrap().remove("col");
                }),
                "site.span.start.col: is required",
            ),
            (
                Box::new(|s| s["span"]["end"] = json!("x")),
                "site.span.end: must be an object",
            ),
            (
                Box::new(|s| s["spell"] = json!("mutate")),
                "site.spell: `mutate` is not a spell",
            ),
            (
                Box::new(|s| s["original"] = json!(1)),
                "site.original: must be a string",
            ),
            (
                Box::new(|s| s["replacement"] = json!(1)),
                "site.replacement: must be a string",
            ),
            (
                Box::new(|s| s["reload"] = json!("other")),
                "site.reload: must be `module` or `dependents`",
            ),
        ];
        for (change, detail) in cases {
            assert_eq!(bad_field(&cast_with(change)), detail);
        }
    }

    #[test]
    fn what_a_site_may_leave_out_it_may_leave_out() {
        for change in [
            (|s: &mut Value| {
                s.as_object_mut().unwrap().remove("enclosing");
            }) as fn(&mut Value),
            |s| s["ordinal"] = Value::Null,
            |s| {
                s.as_object_mut().unwrap().remove("original");
            },
            |s| {
                s["span"].as_object_mut().unwrap().remove("end");
            },
            |s| s["span"]["end"] = Value::Null,
            |s| s["reload"] = json!("dependents"),
        ] {
            assert!(decode(&cast_with(change)).is_ok());
        }
    }

    #[test]
    fn a_scope_is_exactly_one_of_all_since_or_files() {
        let delegate = |scope: &str| format!(r#"{{"type":"delegate","id":1,"scope":{scope}}}"#);

        for ok in [
            r#"{"all":true}"#,
            r#"{"since":"main"}"#,
            r#"{"files":["a"]}"#,
        ] {
            assert_eq!(decode(&delegate(ok)).unwrap().1, Request::Delegate, "{ok}");
        }
        for (scope, detail) in [
            ("1", "scope: must be an object"),
            ("{}", "scope: needs one of `all`, `since` or `files`"),
            (
                r#"{"all":true,"since":"x"}"#,
                "scope: takes exactly one of `all`, `since` or `files`",
            ),
            (r#"{"all":false}"#, "scope.all: can only be `true`"),
            (r#"{"since":1}"#, "scope.since: must be a string"),
            (r#"{"files":"a"}"#, "scope.files: must be an array"),
        ] {
            assert_eq!(bad_field(&delegate(scope)), detail, "{scope}");
        }
        assert_eq!(
            bad_field(r#"{"type":"delegate","id":1}"#),
            "scope: is required"
        );
    }

    fn reply(line: &str) -> Value {
        serde_json::from_str(line).unwrap()
    }

    #[test]
    fn replies_carry_their_type_and_id() {
        let prepared = reply(&prepared(3, 41, 2));
        assert_eq!(prepared["type"], "prepared");
        assert_eq!(prepared["duration_ms"], 41);
        assert_eq!(prepared["modules"], 2);

        let outcomes = [
            (Outcome::Survived, "survived"),
            (Outcome::Killed { by: "t".into() }, "killed"),
            (
                Outcome::CompileError {
                    message: "m".into(),
                },
                "compile_error",
            ),
        ];
        for (outcome, wire) in outcomes {
            let done = reply(&cast_done(5, "w", &outcome, 7));
            assert_eq!(done["type"], "cast_done");
            assert_eq!(done["id"], 5);
            assert_eq!(done["wekufe"], "w");
            assert_eq!(done["outcome"], wire);
            assert_eq!(done["duration_ms"], 7);
            assert_eq!(done["dirty"], false);
        }
        let killed = reply(&cast_done(5, "w", &Outcome::Killed { by: "t".into() }, 7));
        assert_eq!(killed["killed_by"], "t");
        let compile = reply(&cast_done(
            5,
            "w",
            &Outcome::CompileError {
                message: "m".into(),
            },
            7,
        ));
        assert_eq!(compile["message"], "m");

        let bye = reply(&bye(8));
        assert_eq!(
            (bye["type"].as_str(), bye["id"].as_i64()),
            (Some("bye"), Some(8))
        );
        let err = reply(&error(2, "c", "m", true));
        assert_eq!(err["type"], "error");
        assert_eq!(err["code"], "c");
        assert_eq!(err["fatal"], true);
    }

    #[test]
    fn a_baseline_is_green_without_failures_and_red_with_them() {
        let timed = [Timed {
            test: "a::t".into(),
            file: "a.rs".into(),
            duration_ms: 4,
        }];
        let green = reply(&baseline_done(3, 10, &timed, &[], None));
        assert_eq!(green["type"], "baseline_done");
        assert_eq!(green["status"], "green");
        assert_eq!(green["duration_ms"], 10);
        assert_eq!(
            green["tests"],
            json!([{"test": "a::t", "file": "a.rs", "duration_ms": 4}])
        );

        let failure = Failure {
            test: "a::t".into(),
            message: "boom".into(),
        };
        let red = reply(&baseline_done(3, 10, &timed, &[failure], None));
        assert_eq!(red["status"], "red");
        assert_eq!(
            red["failures"],
            json!([{"test": "a::t", "message": "boom"}])
        );
    }

    #[test]
    fn a_skipped_file_is_reported_with_its_reason() {
        let skipped = [Skipped {
            file: "a.rs".into(),
            reason: "parse_error".into(),
            message: "line 1".into(),
        }];

        let found = reply(&sites_found(4, &[], &skipped));

        assert_eq!(found["type"], "sites_found");
        assert_eq!(
            found["skipped"],
            json!([{"file": "a.rs", "reason": "parse_error", "message": "line 1"}])
        );
    }

    #[test]
    fn a_baseline_carries_its_coverage_inline_or_by_path_and_never_both() {
        let entry = Entry {
            file: "a.rs".into(),
            line: 3,
            tests: vec!["a::t".into()],
        };

        let inline = reply(&baseline_done(
            3,
            1,
            &[],
            &[],
            Some(&Coverage::Inline(vec![entry])),
        ));
        assert_eq!(
            inline["coverage"],
            json!([{"file": "a.rs", "line": 3, "tests": ["a::t"]}])
        );
        assert!(inline.get("coverage_path").is_none());

        let by_path = reply(&baseline_done(
            3,
            1,
            &[],
            &[],
            Some(&Coverage::Path("/reni/coverage/0.json".into())),
        ));
        assert_eq!(by_path["coverage_path"], "/reni/coverage/0.json");
        assert!(by_path.get("coverage").is_none());

        let none = reply(&baseline_done(3, 1, &[], &[], None));
        assert!(none.get("coverage").is_none() && none.get("coverage_path").is_none());
    }

    #[test]
    fn a_ready_announces_exactly_the_capabilities_it_is_given() {
        let said = reply(&ready(1, "0", "r", &["cast", "abort", "per_test_coverage"]));

        assert_eq!(
            said["capabilities"],
            json!(["cast", "abort", "per_test_coverage"])
        );
        assert_eq!(reply(&ready(1, "0", "r", &[]))["capabilities"], json!([]));
    }
}
