//! This kalku against the protocol's own fixtures: the same lines the
//! kaikai side and the Elixir kalku are held to.

use kalku_rust::framing::{Line, read_line};
use kalku_rust::protocol::{self, decode};
use serde_json::Value;
use std::fs;
use std::io::Cursor;
use std::path::{Path, PathBuf};

fn fixtures() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../docs/protocol/fixtures")
}

fn lines(path: &Path) -> Vec<String> {
    fs::read_to_string(path)
        .unwrap_or_else(|_| panic!("no fixture {}", path.display()))
        .lines()
        .map(str::to_string)
        .collect()
}

// Every request a kalku can be sent decodes.
#[test]
fn every_request_fixture_decodes() {
    let dir = fixtures().join("kalku/requests");
    let mut seen = 0;
    for entry in fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        for line in lines(&path) {
            seen += 1;
            if let Err(e) = decode(&line) {
                panic!("{}: {e:?}\n  {line}", path.display());
            }
        }
    }
    assert!(seen >= 10, "only {seen} request fixtures were read");
}

// A malformed line is refused with the kind the fixture names.
#[test]
fn every_invalid_request_is_refused_with_its_kind() {
    let path = fixtures().join("invalid/kalku_requests.ndjson");
    for entry in lines(&path) {
        let v: Value = serde_json::from_str(&entry).unwrap();
        let (expect, line) = (v["expect"].as_str().unwrap(), v["line"].as_str().unwrap());

        match decode(line) {
            Ok(_) => panic!("accepted a line that should be `{expect}`: {line}"),
            Err(e) => assert_eq!(e.kind.name(), expect, "{line}"),
        }
    }
}

// One oversized message costs only itself.
#[test]
fn framing_matches_its_fixtures() {
    let path = fixtures().join("invalid/framing.ndjson");
    for entry in lines(&path) {
        let v: Value = serde_json::from_str(&entry).unwrap();
        let (expect, max) = (
            v["expect"].as_str().unwrap(),
            v["max"].as_u64().unwrap() as usize,
        );
        let mut reader = Cursor::new(v["input"].as_str().unwrap().as_bytes().to_vec());

        let mut got = Vec::new();
        while let Some(l) = read_line(&mut reader, max).unwrap() {
            got.push(l);
        }

        let too_long = got.iter().any(|l| matches!(l, Line::TooLong));
        assert_eq!(too_long, expect == "line_too_long", "{entry}");
        // The last line is always readable, whatever came before it.
        assert!(
            matches!(got.last(), Some(Line::Text(t)) if t.contains("\"id\":8")),
            "{entry}"
        );
    }
}

// What this kalku says is canonical: it matches the fixtures byte for byte.
#[test]
fn what_it_says_is_the_fixtures_byte_for_byte() {
    let replies = fixtures().join("kalku/replies");
    let said = |name: &str| lines(&replies.join(format!("{name}.ndjson")));

    assert!(said("error").contains(&protocol::error(
        1,
        "protocol_mismatch",
        "kalku speaks protocol 2, kaikai side speaks 1",
        true
    )));
    assert!(said("error").contains(&protocol::error(
        5,
        "unknown_test",
        "no test test/my_app/parser_test.exs:99",
        false
    )));
    assert!(said("bye").contains(&protocol::bye(7)));
    assert!(said("sites_found").contains(&protocol::sites_found(4, &[], &[])));
}

// `type`, then `id`, then the fields in the order the tables list them.
#[test]
fn a_ready_reply_is_in_canonical_order() {
    let said = protocol::ready(1, "0.3.0", "rustc 1.98.1, edition 2024", &["cast", "abort"]);
    let v: Value = serde_json::from_str(&said).unwrap();
    let keys: Vec<&str> = v.as_object().unwrap().keys().map(String::as_str).collect();

    assert_eq!(
        keys,
        [
            "type",
            "id",
            "protocol",
            "language",
            "adapter",
            "runtime",
            "spells",
            "capabilities"
        ]
    );
    assert_eq!(v["language"], "rust");
    assert_eq!(v["capabilities"], serde_json::json!(["cast", "abort"]));
    // No insignificant whitespace: it is its own compact form.
    assert_eq!(said, serde_json::to_string(&v).unwrap());
}

// The spells announced are the ones this kalku can cast, by their wire names.
#[test]
fn the_spells_it_announces_are_the_shared_names() {
    let v: Value = serde_json::from_str(&protocol::ready(1, "0", "r", &["cast"])).unwrap();

    assert_eq!(
        v["spells"],
        serde_json::json!(["arm", "compare", "connect", "negate", "literal"])
    );
}
