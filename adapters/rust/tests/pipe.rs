//! The kalku as the kaikai side meets it: a real process, a real pipe, and
//! real Cargo projects on disk. Every line it writes has to be a protocol
//! message — one that is not would have it banished for writing nonsense.

use serde_json::{Value, json};
use std::fs;
use std::io::{BufRead, BufReader, Write};
use std::path::PathBuf;
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};

struct Kalku {
    child: Child,
    stdin: ChildStdin,
    stdout: BufReader<ChildStdout>,
}

impl Kalku {
    fn summon() -> Kalku {
        let mut child = Command::new(env!("CARGO_BIN_EXE_kalku-rust"))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .expect("the binary starts");
        let stdin = child.stdin.take().unwrap();
        let stdout = BufReader::new(child.stdout.take().unwrap());
        Kalku {
            child,
            stdin,
            stdout,
        }
    }

    // One request, one reply; and the reply is always a JSON object.
    fn ask(&mut self, request: Value) -> Value {
        self.say(&request.to_string())
    }

    fn say(&mut self, line: &str) -> Value {
        writeln!(self.stdin, "{line}").unwrap();
        self.stdin.flush().unwrap();
        let mut reply = String::new();
        self.stdout.read_line(&mut reply).unwrap();
        let v: Value = serde_json::from_str(reply.trim_end()).unwrap_or_else(|e| {
            panic!("stdout carried something that is not JSON ({e}): {reply:?}")
        });
        assert!(v.is_object(), "{reply}");
        v
    }

    fn hello(&mut self, root: &PathBuf) -> Value {
        self.ask(json!({
            "type": "hello", "id": 1, "protocol": 1,
            "root": root, "reni": root.join(".reni"), "worker": 0,
            "inline_limit_bytes": 65536, "env": {}
        }))
    }

    fn sites(&mut self, files: &[&str]) -> Value {
        self.ask(json!({
            "type": "sites", "id": 2, "files": files,
            "spells": ["arm", "compare", "connect", "negate", "literal"],
            "exclude_calls": []
        }))
    }

    fn leave(mut self) -> i32 {
        let bye = self.ask(json!({"type": "shutdown", "id": 99}));
        assert_eq!(bye["type"], "bye");
        self.child.wait().unwrap().code().unwrap_or(-1)
    }
}

// A project on disk, removed when the test ends.
struct Project(PathBuf);

impl Project {
    fn new(name: &str, edition: &str, files: &[(&str, &str)]) -> Project {
        let root = std::env::temp_dir().join(format!("kalku_rust_{}_{name}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(root.join("src")).unwrap();
        fs::write(
            root.join("Cargo.toml"),
            format!("[package]\nname = \"p\"\nversion = \"0.1.0\"\nedition = \"{edition}\"\n"),
        )
        .unwrap();
        for (path, text) in files {
            fs::write(root.join(path), text).unwrap();
        }
        Project(root)
    }
}

impl Drop for Project {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

const LIB: &str = "pub fn gate(a: i32) -> bool { a >= 1 && a < 9 }\n";

#[test]
fn it_greets_finds_sites_and_leaves() {
    let project = Project::new("greets", "2024", &[("src/lib.rs", LIB)]);
    let mut k = Kalku::summon();

    let ready = k.hello(&project.0);
    assert_eq!(ready["type"], "ready");
    assert_eq!(ready["language"], "rust");
    assert_eq!(ready["capabilities"], json!(["cast"]));
    assert!(ready["runtime"].as_str().unwrap().contains("edition 2024"));

    let found = k.sites(&["src/lib.rs"]);
    assert_eq!(found["type"], "sites_found");
    assert_eq!(found["skipped"], json!([]));
    let sites = found["sites"].as_array().unwrap();
    assert!(
        sites
            .iter()
            .any(|s| s["spell"] == "compare" && s["original"] == ">=")
    );
    assert!(
        sites
            .iter()
            .any(|s| s["spell"] == "connect" && s["original"] == "&&")
    );
    assert!(
        sites
            .iter()
            .all(|s| s["file"] == "src/lib.rs" && s["enclosing"] == "gate")
    );

    assert_eq!(k.leave(), 0);
}

// kalku measures the 2024 edition only. An older package is not read with
// rules that may not apply to it; it is skipped, and says why.
#[test]
fn a_package_on_an_older_edition_is_skipped_with_its_reason() {
    let project = Project::new("old", "2021", &[("src/lib.rs", LIB)]);
    let mut k = Kalku::summon();
    k.hello(&project.0);

    let found = k.sites(&["src/lib.rs"]);

    assert_eq!(found["sites"], json!([]));
    assert_eq!(found["skipped"][0]["file"], "src/lib.rs");
    assert_eq!(found["skipped"][0]["reason"], "unsupported_edition");
    assert!(
        found["skipped"][0]["message"]
            .as_str()
            .unwrap()
            .contains("2024")
    );
    k.leave();
}

// One file that cannot be read must not cost the rest.
#[test]
fn a_file_that_does_not_parse_is_skipped_and_the_rest_are_searched() {
    let project = Project::new(
        "broken",
        "2024",
        &[
            ("src/lib.rs", LIB),
            ("src/broken.rs", "fn f( {\n"),
            ("README.md", "# hi\n"),
        ],
    );
    let mut k = Kalku::summon();
    k.hello(&project.0);

    let found = k.sites(&["src/lib.rs", "src/broken.rs", "README.md"]);

    assert!(!found["sites"].as_array().unwrap().is_empty());
    let reasons: Vec<_> = found["skipped"]
        .as_array()
        .unwrap()
        .iter()
        .map(|s| (s["file"].as_str().unwrap(), s["reason"].as_str().unwrap()))
        .collect();
    assert_eq!(
        reasons,
        [("src/broken.rs", "parse_error"), ("README.md", "not_rust")]
    );
    k.leave();
}

#[test]
fn a_request_before_hello_is_refused_without_ending_the_conversation() {
    let project = Project::new("early", "2024", &[("src/lib.rs", LIB)]);
    let mut k = Kalku::summon();

    let early = k.sites(&["src/lib.rs"]);
    assert_eq!(early["type"], "error");
    assert_eq!(early["code"], "not_ready");
    assert_eq!(early["fatal"], false);

    assert_eq!(k.hello(&project.0)["type"], "ready");
    k.leave();
}

// A line it cannot read is answered, and the next one is still heard.
#[test]
fn a_garbled_line_is_answered_and_the_conversation_carries_on() {
    let project = Project::new("garbled", "2024", &[("src/lib.rs", LIB)]);
    let mut k = Kalku::summon();

    let bad = k.say("this is not json");
    assert_eq!(bad["code"], "bad_request");
    assert!(bad["message"].as_str().unwrap().starts_with("not_json"));

    assert_eq!(k.hello(&project.0)["type"], "ready");
    k.leave();
}

#[test]
fn a_protocol_it_does_not_speak_is_refused_by_name() {
    let project = Project::new("proto", "2024", &[("src/lib.rs", LIB)]);
    let mut k = Kalku::summon();

    let refused = k.ask(json!({
        "type": "hello", "id": 1, "protocol": 2, "root": project.0, "reni": "/r",
        "worker": 0, "inline_limit_bytes": 1, "env": {}
    }));

    assert_eq!(refused["code"], "protocol_mismatch");
    assert_eq!(refused["fatal"], true);
    k.leave();
}

// A kalku announces `cast`, so it answers everything that implies; until it
// does, it says so plainly rather than going quiet.
#[test]
fn what_it_cannot_do_yet_is_said_plainly() {
    let project = Project::new("later", "2024", &[("src/lib.rs", LIB)]);
    let mut k = Kalku::summon();
    k.hello(&project.0);

    let said = k.ask(json!({"type": "prepare", "id": 3}));

    assert_eq!(said["type"], "error");
    assert_eq!(said["code"], "not_implemented");
    assert!(said["message"].as_str().unwrap().contains("prepare"));
    k.leave();
}

// A root reached through a link — `/tmp` and `/var` are links on macOS —
// names its files differently from the real path Cargo reports, and a file
// compared by the name it was given belongs to no package at all.
#[cfg(unix)]
#[test]
fn a_root_reached_through_a_symlink_still_finds_its_packages() {
    let project = Project::new("linked", "2024", &[("src/lib.rs", LIB)]);
    let link = std::env::temp_dir().join(format!("kalku_rust_{}_link", std::process::id()));
    let _ = fs::remove_file(&link);
    std::os::unix::fs::symlink(&project.0, &link).unwrap();

    let mut k = Kalku::summon();
    k.hello(&link);
    let found = k.sites(&["src/lib.rs"]);
    let _ = fs::remove_file(&link);

    assert_eq!(found["skipped"], json!([]), "{found}");
    assert!(!found["sites"].as_array().unwrap().is_empty());
    k.leave();
}
