//! The kalku protocol: one JSON object per line.
//!
//! Decoding is strict where the protocol is and lenient where it says to be:
//! fields in any order, unknown fields ignored, a missing or `null` optional
//! field absent — and a required field that is missing, of the wrong shape,
//! or outside a closed set an error that names its path. Encoding is
//! canonical: `type`, then `id`, then the fields in the order the tables
//! list them, absent optionals omitted, no whitespace.

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

pub fn ready(id: i64, adapter: &str, runtime: &str) -> String {
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
            ("capabilities", vec![Value::from("cast")].into()),
        ],
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
