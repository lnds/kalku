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
        Kalku::summon_with(&[])
    }

    // With these variables in its environment, beside the ones it was started in.
    fn summon_with(env: &[(&str, &str)]) -> Kalku {
        let mut child = Command::new(env!("CARGO_BIN_EXE_kalku-rust"))
            .envs(env.iter().copied())
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
    // Coverage is announced only where the LLVM tools are, so the rest is fixed.
    assert_eq!(
        ready["capabilities"].as_array().unwrap()[..2],
        [json!("cast"), json!("abort")]
    );
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

// What it cannot do yet is said plainly rather than going quiet.
#[test]
fn what_it_cannot_do_yet_is_said_plainly() {
    let project = Project::new("later", "2024", &[("src/lib.rs", LIB)]);
    let mut k = Kalku::summon();
    k.hello(&project.0);

    let said = k.ask(json!({"type": "reset", "id": 3}));

    assert_eq!(said["type"], "error");
    assert_eq!(said["code"], "not_implemented");
    assert!(said["message"].as_str().unwrap().contains("reset"));
    k.leave();
}

const TESTED: &str = "pub fn gate(a: i32) -> bool { a >= 1 }\n\
    #[cfg(test)]\nmod tests {\n    use super::*;\n\
    #[test]\n    fn one_is_inside() { assert!(gate(1)); }\n\
    #[test]\n    fn zero_is_outside() { assert!(!gate(0)); }\n}\n";

fn site(k: &mut Kalku, spell: &str, original: &str) -> Value {
    let found = k.sites(&["src/lib.rs"]);
    found["sites"]
        .as_array()
        .unwrap()
        .iter()
        .find(|s| s["spell"] == spell && s["original"] == original)
        .unwrap_or_else(|| panic!("no {spell} site for {original}: {found}"))
        .clone()
}

// A test the project marks `#[ignore]` is one it asked not to be run. Run
// alone it reports neither a pass nor a failure, and taking that for a
// failure calls a green suite red.
#[test]
fn a_test_the_project_ignores_is_not_part_of_the_suite() {
    let ignoring = TESTED.replace(
        "#[test]\n    fn zero_is_outside()",
        "#[test]\n    #[ignore = \"needs a database\"]\n    fn zero_is_outside()",
    );
    assert_ne!(ignoring, TESTED);
    let project = Project::new("ignores", "2024", &[("src/lib.rs", ignoring.as_str())]);
    let mut k = Kalku::summon();
    k.hello(&project.0);
    let prepared = k.ask(json!({"type": "prepare", "id": 2}));
    assert_eq!(prepared["type"], "prepared", "{prepared}");

    let baseline = k.ask(json!({"type": "baseline", "id": 3}));
    assert_eq!(baseline["status"], "green", "{baseline}");
    assert_eq!(baseline["failures"], json!([]));
    let tests: Vec<&str> = baseline["tests"]
        .as_array()
        .unwrap()
        .iter()
        .map(|t| t["test"].as_str().unwrap())
        .collect();
    assert_eq!(tests, ["src/lib.rs::tests::one_is_inside"]);
    k.leave();
}

// The whole loop on a real project: prepare in the reni, a baseline with a
// duration per test, a wekufe that a test kills, one that survives, one
// that does not compile, and the user's tree never touched.
#[test]
fn it_prepares_measures_and_casts_in_the_reni() {
    let project = Project::new("casts", "2024", &[("src/lib.rs", TESTED)]);
    let mut k = Kalku::summon();
    k.hello(&project.0);

    let prepared = k.ask(json!({"type": "prepare", "id": 2}));
    assert_eq!(prepared["type"], "prepared", "{prepared}");

    let baseline = k.ask(json!({"type": "baseline", "id": 3}));
    assert_eq!(baseline["status"], "green", "{baseline}");
    let tests: Vec<String> = baseline["tests"]
        .as_array()
        .unwrap()
        .iter()
        .map(|t| t["test"].as_str().unwrap().to_string())
        .collect();
    assert_eq!(
        tests,
        [
            "src/lib.rs::tests::one_is_inside",
            "src/lib.rs::tests::zero_is_outside"
        ]
    );

    // `>=` to `>`: the test of one is inside kills it.
    let mut weaker = site(&mut k, "compare", ">=");
    weaker["replacement"] = ">".into();
    let cast = |k: &mut Kalku, id: i64, site: &Value| {
        k.ask(json!({"type": "cast", "id": id, "wekufe": site["site_id"],
            "site": site, "tests": tests}))
    };
    let killed = cast(&mut k, 4, &weaker);
    assert_eq!(killed["outcome"], "killed", "{killed}");
    assert_eq!(killed["killed_by"], "src/lib.rs::tests::one_is_inside");
    assert_eq!(killed["dirty"], false);

    // The next cast starts from the original, not from the last wekufe.
    let mut same = weaker.clone();
    same["replacement"] = ">=".into();
    let survived = cast(&mut k, 5, &same);
    assert_eq!(survived["outcome"], "survived", "{survived}");

    let mut broken = weaker.clone();
    broken["replacement"] = "=>".into();
    let failed = cast(&mut k, 6, &broken);
    assert_eq!(failed["outcome"], "compile_error", "{failed}");
    assert!(!failed["message"].as_str().unwrap().is_empty());

    let after_error = cast(&mut k, 7, &weaker);
    assert_eq!(after_error["outcome"], "killed", "{after_error}");

    assert_eq!(
        fs::read_to_string(project.0.join("src/lib.rs")).unwrap(),
        TESTED
    );
    assert!(!project.0.join("target").exists());
    k.leave();
}

#[test]
fn a_cast_before_a_baseline_is_not_ready_and_a_stale_site_is_refused() {
    let project = Project::new("stale", "2024", &[("src/lib.rs", TESTED)]);
    let mut k = Kalku::summon();
    k.hello(&project.0);
    let site = site(&mut k, "compare", ">=");
    let ask = |k: &mut Kalku, id: i64, site: &Value, tests: Value| {
        k.ask(json!({"type": "cast", "id": id, "wekufe": site["site_id"],
            "site": site, "tests": tests}))
    };

    let early = ask(&mut k, 2, &site, json!([]));
    assert_eq!(early["code"], "not_ready");

    k.ask(json!({"type": "prepare", "id": 3}));
    k.ask(json!({"type": "baseline", "id": 4}));
    let unknown = ask(&mut k, 5, &site, json!(["src/lib.rs::tests::nope"]));
    assert_eq!(unknown["code"], "unknown_test");

    let mut moved = site.clone();
    moved["original"] = "<=".into();
    let stale = ask(
        &mut k,
        6,
        &moved,
        json!(["src/lib.rs::tests::one_is_inside"]),
    );
    assert_eq!(stale["code"], "bad_request");
    assert_eq!(stale["fatal"], false);
    k.leave();
}

#[test]
fn a_project_that_does_not_compile_is_a_prepare_failure_not_an_exit() {
    let project = Project::new("wontbuild", "2024", &[("src/lib.rs", "pub fn f( {\n")]);
    let mut k = Kalku::summon();
    k.hello(&project.0);

    let said = k.ask(json!({"type": "prepare", "id": 2}));

    assert_eq!(said["code"], "prepare_failed");
    assert_eq!(said["fatal"], true);
    assert!(!said["message"].as_str().unwrap().is_empty());
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

// Workers share one reni, so each keeps its own copy and its own build
// directory in it: two casts at once must not write into the same tree.
#[test]
fn workers_sharing_a_reni_each_keep_their_own_copy() {
    let project = Project::new("workers", "2024", &[("src/lib.rs", TESTED)]);
    let reni = project.0.join(".reni");
    let hello = |k: &mut Kalku, worker: i64| {
        k.ask(json!({
            "type": "hello", "id": 1, "protocol": 1, "root": project.0, "reni": reni,
            "worker": worker, "inline_limit_bytes": 65536, "env": {}
        }))
    };
    let (mut a, mut b) = (Kalku::summon(), Kalku::summon());
    hello(&mut a, 0);
    hello(&mut b, 1);

    let (pa, pb) = (
        a.ask(json!({"type": "prepare", "id": 2})),
        b.ask(json!({"type": "prepare", "id": 2})),
    );

    assert_eq!(pa["type"], "prepared", "{pa}");
    assert_eq!(pb["type"], "prepared", "{pb}");
    assert!(reni.join("work/0/src/lib.rs").exists());
    assert!(reni.join("work/1/src/lib.rs").exists());
    a.leave();
    b.leave();
}

// The kaikai side asks one kalku for the baseline and only prepares the
// rest, so a kalku that was never asked for one must still cast.
#[test]
fn a_kalku_that_was_only_prepared_can_cast() {
    let project = Project::new("prepared", "2024", &[("src/lib.rs", TESTED)]);
    let mut k = Kalku::summon();
    k.hello(&project.0);
    let mut weaker = site(&mut k, "compare", ">=");
    weaker["replacement"] = ">".into();

    let prepared = k.ask(json!({"type": "prepare", "id": 2}));
    assert_eq!(prepared["type"], "prepared", "{prepared}");
    let cast = k.ask(json!({"type": "cast", "id": 3, "wekufe": weaker["site_id"],
        "site": weaker, "tests": ["src/lib.rs::tests::one_is_inside"]}));

    assert_eq!(cast["outcome"], "killed", "{cast}");
    k.leave();
}

// A test that, once told to, writes the id of its own process where the test
// below can see it and then sleeps for a minute.
const SLOW: &str = "pub fn gate(a: i32) -> bool { a >= 1 }\n\
    #[cfg(test)]\nmod tests {\n    use super::*;\n\
    #[test]\n    fn slow() {\n\
        assert!(gate(1));\n\
        if let Ok(path) = std::fs::read_to_string(\"flag\") {\n\
            std::fs::write(path.trim(), std::process::id().to_string()).unwrap();\n\
            std::thread::sleep(std::time::Duration::from_secs(60));\n\
        }\n    }\n}\n";

impl Kalku {
    fn send(&mut self, request: Value) {
        writeln!(self.stdin, "{request}").unwrap();
        self.stdin.flush().unwrap();
    }

    fn read(&mut self) -> Value {
        let mut line = String::new();
        self.stdout.read_line(&mut line).unwrap();
        serde_json::from_str(line.trim_end()).unwrap_or_else(|e| panic!("{e}: {line:?}"))
    }
}

// A killed process that its new parent has not reaped yet is a zombie: it
// still answers `kill -0`, but it is not running.
#[cfg(unix)]
fn alive(pid: i32) -> bool {
    let out = Command::new("ps")
        .args(["-o", "stat=", "-p", &pid.to_string()])
        .output()
        .unwrap();
    let state = String::from_utf8_lossy(&out.stdout);
    let state = state.trim();
    !state.is_empty() && !state.starts_with('Z')
}

// The timeout is the kaikai side's, and what it says is `abort`. The cast
// that is running stops, what it started dies with it, the file is back, and
// the same kalku goes on to the next cast.
#[cfg(unix)]
#[test]
fn an_abort_stops_a_running_cast_and_the_kalku_casts_again() {
    let project = Project::new("aborts", "2024", &[("src/lib.rs", SLOW)]);
    let mut k = Kalku::summon();
    let capabilities = k.hello(&project.0)["capabilities"].clone();
    assert_eq!(
        capabilities.as_array().unwrap()[..2],
        [json!("cast"), json!("abort")]
    );
    k.ask(json!({"type": "prepare", "id": 2}));
    let baseline = k.ask(json!({"type": "baseline", "id": 3}));
    assert_eq!(baseline["status"], "green", "{baseline}");
    let weaker = {
        let mut s = site(&mut k, "compare", ">=");
        s["replacement"] = ">=".into();
        s
    };
    // From here on the test sleeps, and says where it is.
    let pidfile = project.0.join("slow.pid");
    fs::write(
        project.0.join(".reni/work/0/flag"),
        pidfile.to_str().unwrap(),
    )
    .unwrap();

    k.send(
        json!({"type": "cast", "id": 10, "wekufe": "w", "site": weaker,
        "tests": ["src/lib.rs::tests::slow"]}),
    );
    let started = std::time::Instant::now();
    while !pidfile.exists() {
        assert!(started.elapsed().as_secs() < 90, "the cast never started");
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
    let pid: i32 = fs::read_to_string(&pidfile)
        .unwrap()
        .trim()
        .parse()
        .unwrap();
    k.send(json!({"type": "abort", "id": 11, "cast": 10}));

    // The first thing said is the abort's answer: the cast gets none.
    let aborted = k.read();
    assert_eq!(aborted["type"], "aborted", "{aborted}");
    assert_eq!(aborted["cast"], 10);
    assert_eq!(aborted["restored"], true);
    assert!(started.elapsed().as_secs() < 30);
    // The kill is a signal, and the process takes a moment to go.
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
    while alive(pid) && std::time::Instant::now() < deadline {
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
    assert!(!alive(pid), "the test the cast started outlived the abort");

    // Same kalku, next cast: the file was put back and the build is sound.
    fs::remove_file(project.0.join(".reni/work/0/flag")).unwrap();
    let again = k.ask(
        json!({"type": "cast", "id": 12, "wekufe": "w", "site": weaker,
        "tests": ["src/lib.rs::tests::slow"]}),
    );
    assert_eq!(again["outcome"], "survived", "{again}");
    assert_eq!(
        fs::read_to_string(project.0.join("src/lib.rs")).unwrap(),
        SLOW
    );
    k.leave();
}

// The id a process wrote of itself, once it has.
#[cfg(unix)]
fn said(pidfile: &std::path::Path) -> i32 {
    let started = std::time::Instant::now();
    loop {
        if let Some(pid) = fs::read_to_string(pidfile)
            .ok()
            .and_then(|s| s.trim().parse().ok())
        {
            return pid;
        }
        assert!(started.elapsed().as_secs() < 120, "nothing was started");
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
}

#[cfg(unix)]
fn parent(pid: i32) -> i32 {
    let out = Command::new("ps")
        .args(["-o", "ppid=", "-p", &pid.to_string()])
        .output()
        .unwrap();
    String::from_utf8_lossy(&out.stdout).trim().parse().unwrap()
}

// The run that summoned the kalku is killed: the input ends with no
// `shutdown` on it. What is still there two seconds later is named, and
// killed, so that a failure leaves nothing running.
#[cfg(unix)]
fn orphan(k: Kalku, started: &[i32]) -> Vec<String> {
    let Kalku {
        mut child, stdin, ..
    } = k;
    drop(stdin);
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
    let late = || std::time::Instant::now() > deadline;
    let pause = || std::thread::sleep(std::time::Duration::from_millis(20));
    let mut left = Vec::new();
    while child.try_wait().unwrap().is_none() {
        if late() {
            left.push("the kalku".to_string());
            child.kill().unwrap();
            child.wait().unwrap();
            break;
        }
        pause();
    }
    for &pid in started {
        while alive(pid) && !late() {
            pause();
        }
        if alive(pid) {
            left.push(format!("process {pid}"));
            // SAFETY: a signal to a process this test saw the kalku start.
            unsafe {
                libc::kill(pid, libc::SIGKILL);
            }
        }
    }
    left
}

// From here on the slow test sleeps, and says where it is.
#[cfg(unix)]
fn slow_from_now(project: &Project) -> PathBuf {
    let pidfile = project.0.join("slow.pid");
    fs::write(
        project.0.join(".reni/work/0/flag"),
        pidfile.to_str().unwrap(),
    )
    .unwrap();
    pidfile
}

#[cfg(unix)]
#[test]
fn a_kalku_whose_run_is_gone_ends_in_the_middle_of_a_baseline_with_what_it_started() {
    let project = Project::new("orphan_baseline", "2024", &[("src/lib.rs", SLOW)]);
    let mut k = Kalku::summon();
    k.hello(&project.0);
    k.ask(json!({"type": "prepare", "id": 2}));
    let pidfile = slow_from_now(&project);

    k.send(json!({"type": "baseline", "id": 3}));
    let test = said(&pidfile);
    let cargo = parent(test);

    assert_eq!(orphan(k, &[test, cargo]), Vec::<String>::new());
}

#[cfg(unix)]
#[test]
fn a_kalku_whose_run_is_gone_ends_in_the_middle_of_a_cast_and_the_next_run_starts_clean() {
    let project = Project::new("orphan_cast", "2024", &[("src/lib.rs", SLOW)]);
    let mut k = Kalku::summon();
    k.hello(&project.0);
    k.ask(json!({"type": "prepare", "id": 2}));
    k.ask(json!({"type": "baseline", "id": 3}));
    let mut weaker = site(&mut k, "compare", ">=");
    weaker["replacement"] = "==".into();
    let pidfile = slow_from_now(&project);

    k.send(
        json!({"type": "cast", "id": 10, "wekufe": "w", "site": weaker,
        "tests": ["src/lib.rs::tests::slow"]}),
    );
    let test = said(&pidfile);
    let cargo = parent(test);

    assert_eq!(orphan(k, &[test, cargo]), Vec::<String>::new());
    assert_eq!(
        fs::read_to_string(project.0.join("src/lib.rs")).unwrap(),
        SLOW
    );

    // The wekufe is still in the reni's copy, and the next run's `prepare`
    // is what takes it out.
    let copy = project.0.join(".reni/work/0/src/lib.rs");
    assert_ne!(fs::read_to_string(&copy).unwrap(), SLOW);
    let mut next = Kalku::summon();
    next.hello(&project.0);
    let prepared = next.ask(json!({"type": "prepare", "id": 2}));
    assert_eq!(prepared["type"], "prepared", "{prepared}");
    assert_eq!(fs::read_to_string(&copy).unwrap(), SLOW);
    next.leave();
}

#[cfg(unix)]
#[test]
fn a_kalku_whose_run_is_gone_ends_in_the_middle_of_a_prepare_with_the_build_it_started() {
    let project = Project::new("orphan_prepare", "2024", &[("src/lib.rs", LIB)]);
    let pidfile = project.0.join("build.pid");
    // A build script that says where it is and takes a minute.
    fs::write(
        project.0.join("build.rs"),
        format!(
            "fn main() {{\n    std::fs::write({:?}, std::process::id().to_string()).unwrap();\n    \
             std::thread::sleep(std::time::Duration::from_secs(60));\n}}\n",
            pidfile.to_str().unwrap()
        ),
    )
    .unwrap();
    let mut k = Kalku::summon();
    k.hello(&project.0);

    k.send(json!({"type": "prepare", "id": 2}));
    let script = said(&pidfile);
    let cargo = parent(script);

    assert_eq!(orphan(k, &[script, cargo]), Vec::<String>::new());
}

// Input that ends after `shutdown` is a driver that wrote all it had to say
// and closed: every request before it is still answered.
#[test]
fn input_that_ends_after_a_shutdown_has_every_request_answered() {
    let project = Project::new("closes", "2024", &[("src/lib.rs", TESTED)]);
    let mut k = Kalku::summon();
    let root = &project.0;
    let asked = [
        json!({"type": "hello", "id": 1, "protocol": 1, "root": root,
            "reni": root.join(".reni"), "worker": 0, "inline_limit_bytes": 65536, "env": {}}),
        json!({"type": "prepare", "id": 2}),
        json!({"type": "baseline", "id": 3}),
        json!({"type": "shutdown", "id": 4}),
    ];
    for request in &asked {
        writeln!(k.stdin, "{request}").unwrap();
    }
    let Kalku {
        mut child,
        stdin,
        stdout,
    } = k;
    drop(stdin);

    let said: Vec<Value> = stdout
        .lines()
        .map(|line| serde_json::from_str(&line.unwrap()).unwrap())
        .collect();

    let types: Vec<&str> = said.iter().map(|v| v["type"].as_str().unwrap()).collect();
    assert_eq!(types, ["ready", "prepared", "baseline_done", "bye"]);
    assert_eq!(said[2]["status"], "green", "{}", said[2]);
    assert_eq!(child.wait().unwrap().code(), Some(0));
}

// These tests pass only where each thing the kalku says of its run is so.
const UNLIKE: &str = "#[cfg(test)]\nmod tests {\n    use std::path::Path;\n\
    #[test]\n    fn the_copy_leaves_out_what_git_keeps() {\n\
        let here = Path::new(env!(\"CARGO_MANIFEST_DIR\"));\n\
        assert!(here.join(\"src/lib.rs\").exists());\n\
        assert!(!here.join(\".git\").exists());\n    }\n\
    #[test]\n    fn the_build_is_elsewhere() {\n\
        let here = Path::new(env!(\"CARGO_MANIFEST_DIR\"));\n\
        let build = std::env::var(\"CARGO_TARGET_DIR\").unwrap();\n\
        assert!(std::env::current_exe().unwrap().starts_with(&build));\n\
        assert!(!here.join(\"target\").exists());\n    }\n\
    #[test]\n    fn a_variable_of_the_shell_is_not_there() {\n\
        assert!(std::env::var(\"KALKU_TEST_OF_THE_SHELL\").is_err());\n    }\n}\n";

// What a baseline says of its run is what the project's own tests find: the
// copy without `.git`, the build in the reni, the environment cut down.
#[test]
fn what_a_baseline_says_of_its_run_is_what_a_test_finds() {
    let project = Project::new("unlike", "2024", &[("src/lib.rs", UNLIKE)]);
    fs::create_dir_all(project.0.join(".git")).unwrap();
    fs::write(project.0.join(".git/HEAD"), "ref: refs/heads/main\n").unwrap();
    let mut k = Kalku::summon_with(&[("KALKU_TEST_OF_THE_SHELL", "1")]);
    k.hello(&project.0);
    let prepared = k.ask(json!({"type": "prepare", "id": 2}));
    assert_eq!(prepared["type"], "prepared", "{prepared}");

    let baseline = k.ask(json!({"type": "baseline", "id": 3}));

    assert_eq!(baseline["failures"], json!([]), "{baseline}");
    assert_eq!(baseline["tests"].as_array().unwrap().len(), 3);
    let said: Vec<&str> = baseline["differences"]
        .as_array()
        .expect("how the run differed")
        .iter()
        .map(|d| d.as_str().unwrap())
        .collect();
    assert_eq!(said.len(), 5, "{said:?}");
    let reni = project.0.join(".reni");
    assert!(said[0].contains(&format!("{}:", reni.join("work/0").display())));
    assert!(said[1].contains(&reni.join("target").display().to_string()));
    assert!(!said[4].contains("KALKU_TEST_OF_THE_SHELL"));
    k.leave();
}

// Where the LLVM tools are, a baseline says which test reached which line,
// and it is the lines of the project, relative to it, that it names.
#[test]
fn a_baseline_with_coverage_names_the_tests_that_reached_each_line() {
    let project = Project::new("covered", "2024", &[("src/lib.rs", TESTED)]);
    let mut k = Kalku::summon();
    let capabilities = k.hello(&project.0)["capabilities"].clone();
    if !capabilities
        .as_array()
        .unwrap()
        .contains(&json!("per_test_coverage"))
    {
        k.leave();
        return;
    }
    k.ask(json!({"type": "prepare", "id": 2}));

    let baseline = k.ask(json!({"type": "baseline", "id": 3}));

    assert_eq!(baseline["status"], "green", "{baseline}");
    let coverage = baseline["coverage"].as_array().expect("inline coverage");
    let tests_of = |line: u64| -> Vec<String> {
        coverage
            .iter()
            .filter(|e| e["file"] == "src/lib.rs" && e["line"] == line)
            .flat_map(|e| e["tests"].as_array().unwrap().iter())
            .map(|t| t.as_str().unwrap().to_string())
            .collect()
    };
    // Line 1 is `gate`, which both tests call; the others are one test each.
    assert_eq!(
        tests_of(1),
        [
            "src/lib.rs::tests::one_is_inside",
            "src/lib.rs::tests::zero_is_outside"
        ]
    );
    assert_eq!(tests_of(6), ["src/lib.rs::tests::one_is_inside"]);
    assert_eq!(tests_of(8), ["src/lib.rs::tests::zero_is_outside"]);
    k.leave();
}
