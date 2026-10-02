//! The project being measured, built and run inside the reni.
//!
//! Cargo does the building and the running; this module only asks it, and
//! reads what it says. The project is copied into the reni, so a wekufe is
//! spliced into the copy and never into the user's tree, and the build
//! artefacts live there too.
//!
//! Stable libtest has neither per-test timings nor a JSON format, so a
//! test's duration is the time of running it alone, and its result is read
//! from the lines libtest prints.

use serde_json::Value;
use std::collections::HashMap;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

/// A test executable Cargo builds.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Target {
    /// The package it belongs to, as a Cargo package spec.
    pub package: String,
    /// The Cargo flags that select it: `--lib`, `--test pipe`, `--bin x`.
    pub select: Vec<String>,
    /// Its source file, relative to the project root; also what names its
    /// tests, so an id is stable across runs and unique across packages.
    pub file: String,
}

/// What a build said.
#[derive(Debug, PartialEq, Eq)]
pub enum Build {
    Built(Vec<Target>),
    /// The project does not compile; the text is what the compiler said.
    Failed(String),
}

/// One test's result.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Result_ {
    pub name: String,
    pub passed: bool,
    pub message: String,
}

/// What running some tests of one target said.
#[derive(Debug, PartialEq, Eq)]
pub struct Ran {
    pub results: Vec<Result_>,
    pub success: bool,
}

/// What the kalku needs from a project; Cargo is the one implementation.
pub trait Project {
    /// Put a fresh copy of the project in the reni.
    fn copy(&mut self) -> io::Result<()>;
    /// Build every test executable.
    fn build(&mut self) -> io::Result<Build>;
    /// The names of the tests of a target.
    fn list(&mut self, target: &Target) -> io::Result<Vec<String>>;
    /// Run the named tests of a target; no names runs all of them.
    fn run(&mut self, target: &Target, names: &[String]) -> io::Result<Ran>;
    /// The text of a file of the copy.
    fn read(&self, file: &str) -> io::Result<String>;
    /// Overwrite a file of the copy.
    fn write(&mut self, file: &str, text: &str) -> io::Result<()>;
}

pub struct Cargo {
    root: PathBuf,
    reni: PathBuf,
    work: PathBuf,
    target_dir: PathBuf,
    env: Vec<(String, String)>,
}

// What a build needs from the environment of whoever started the kalku;
// everything else is left out, so a test run sees only what it was given.
const INHERITED: [&str; 9] = [
    "PATH",
    "HOME",
    "USER",
    "TMPDIR",
    "CARGO_HOME",
    "RUSTUP_HOME",
    "RUSTUP_TOOLCHAIN",
    "RUSTFLAGS",
    "LANG",
];

impl Cargo {
    pub fn new(root: PathBuf, reni: PathBuf, worker: i64, extra: Vec<(String, String)>) -> Self {
        let mut env: Vec<(String, String)> = INHERITED
            .iter()
            .filter_map(|k| std::env::var(k).ok().map(|v| (k.to_string(), v)))
            .collect();
        env.extend(extra);
        Cargo {
            root,
            work: reni.join("work").join(worker.to_string()),
            target_dir: reni.join("target").join(worker.to_string()),
            reni,
            env,
        }
    }

    fn cargo(&self) -> Command {
        let mut c = Command::new("cargo");
        c.current_dir(&self.work)
            .env_clear()
            .envs(self.env.iter().map(|(k, v)| (k, v)))
            .env("CARGO_TARGET_DIR", &self.target_dir)
            .env("CARGO_TERM_COLOR", "never")
            // stdin is the protocol: a build that read it would eat requests.
            .stdin(Stdio::null())
            // What cargo says while it works is for a human, not the channel.
            .stderr(Stdio::inherit());
        c
    }
}

impl Project for Cargo {
    fn copy(&mut self) -> io::Result<()> {
        if self.work.exists() {
            fs::remove_dir_all(&self.work)?;
        }
        copy_tree(&self.root, &self.work, &self.reni)
    }

    fn build(&mut self) -> io::Result<Build> {
        let out = self
            .cargo()
            .args(["test", "--no-run", "--workspace", "--message-format=json"])
            .output()?;
        let stdout = String::from_utf8_lossy(&out.stdout);
        if out.status.success() {
            Ok(Build::Built(artifacts(&stdout, &self.work)))
        } else {
            let errors = diagnostics(&stdout);
            Ok(Build::Failed(if errors.is_empty() {
                "cargo could not build the project".to_string()
            } else {
                errors
            }))
        }
    }

    fn list(&mut self, target: &Target) -> io::Result<Vec<String>> {
        let out = self
            .cargo()
            .args(["test", "-p", &target.package])
            .args(&target.select)
            .args(["--", "--list", "--format", "terse"])
            .output()?;
        Ok(listing(&String::from_utf8_lossy(&out.stdout)))
    }

    fn run(&mut self, target: &Target, names: &[String]) -> io::Result<Ran> {
        let mut c = self.cargo();
        c.args(["test", "-p", &target.package]).args(&target.select);
        if !names.is_empty() {
            c.arg("--").arg("--exact").args(names);
        }
        let out = c.output()?;
        Ok(Ran {
            results: results(&String::from_utf8_lossy(&out.stdout)),
            success: out.status.success(),
        })
    }

    fn read(&self, file: &str) -> io::Result<String> {
        fs::read_to_string(self.work.join(file))
    }

    fn write(&mut self, file: &str, text: &str) -> io::Result<()> {
        fs::write(self.work.join(file), text)
    }
}

// The project's files, without what Cargo built or what git keeps. The reni
// is skipped in case it is inside the root.
fn copy_tree(from: &Path, to: &Path, reni: &Path) -> io::Result<()> {
    fs::create_dir_all(to)?;
    for entry in fs::read_dir(from)? {
        let entry = entry?;
        let name = entry.file_name();
        let path = entry.path();
        if name == "target" || name == ".git" || path == reni {
            continue;
        }
        let kind = entry.file_type()?;
        let dest = to.join(&name);
        if kind.is_dir() {
            copy_tree(&path, &dest, reni)?;
        } else if kind.is_symlink() {
            #[cfg(unix)]
            std::os::unix::fs::symlink(fs::read_link(&path)?, &dest)?;
        } else {
            fs::copy(&path, &dest)?;
        }
    }
    Ok(())
}

/// The test executables in `cargo test --no-run --message-format=json`.
pub fn artifacts(stdout: &str, work: &Path) -> Vec<Target> {
    let work = fs::canonicalize(work).unwrap_or_else(|_| work.to_path_buf());
    let mut seen: HashMap<String, ()> = HashMap::new();
    let mut targets = Vec::new();
    for message in stdout
        .lines()
        .filter_map(|l| serde_json::from_str::<Value>(l).ok())
    {
        if message["reason"] != "compiler-artifact" || message["profile"]["test"] != true {
            continue;
        }
        if message["executable"].is_null() {
            continue;
        }
        let (Some(package), Some(name), Some(src), Some(kinds)) = (
            message["package_id"].as_str(),
            message["target"]["name"].as_str(),
            message["target"]["src_path"].as_str(),
            message["target"]["kind"].as_array(),
        ) else {
            continue;
        };
        let kind = kinds.first().and_then(Value::as_str).unwrap_or("");
        let select = match kind {
            "lib" | "rlib" | "proc-macro" => vec!["--lib".to_string()],
            "bin" => vec!["--bin".to_string(), name.to_string()],
            "test" => vec!["--test".to_string(), name.to_string()],
            _ => continue,
        };
        let file = Path::new(src)
            .strip_prefix(&work)
            .map(|p| p.to_string_lossy().into_owned())
            .unwrap_or_else(|_| src.to_string());
        if seen.insert(format!("{package} {select:?}"), ()).is_none() {
            targets.push(Target {
                package: package.to_string(),
                select,
                file,
            });
        }
    }
    targets
}

/// The compiler's errors, as the compiler wrote them for a person.
pub fn diagnostics(stdout: &str) -> String {
    stdout
        .lines()
        .filter_map(|l| serde_json::from_str::<Value>(l).ok())
        .filter(|m| m["reason"] == "compiler-message" && m["message"]["level"] == "error")
        .filter_map(|m| m["message"]["rendered"].as_str().map(str::to_string))
        .collect::<Vec<_>>()
        .join("\n")
}

/// The names in `--list --format terse`: `name: test`.
pub fn listing(stdout: &str) -> Vec<String> {
    stdout
        .lines()
        .filter_map(|l| l.strip_suffix(": test"))
        .map(str::to_string)
        .collect()
}

/// The results in what libtest prints, with the message of each failure.
pub fn results(stdout: &str) -> Vec<Result_> {
    let mut out: Vec<Result_> = stdout
        .lines()
        .filter_map(|l| {
            let rest = l.strip_prefix("test ")?;
            let (name, status) = rest.rsplit_once(" ... ")?;
            let passed = match status {
                "ok" => true,
                "FAILED" => false,
                _ => return None,
            };
            Some(Result_ {
                name: name.to_string(),
                passed,
                message: String::new(),
            })
        })
        .collect();
    for r in out.iter_mut().filter(|r| !r.passed) {
        r.message = failure_message(stdout, &r.name);
    }
    out
}

// The text under `---- name stdout ----`, up to the next section.
fn failure_message(stdout: &str, name: &str) -> String {
    let header = format!("---- {name} stdout ----");
    let mut lines = stdout.lines().skip_while(|l| *l != header).skip(1);
    let mut message = Vec::new();
    for l in lines.by_ref() {
        if l.starts_with("---- ") || l == "failures:" {
            break;
        }
        message.push(l);
    }
    let text = message.join("\n");
    let text = text.trim();
    if text.is_empty() {
        "the test failed".to_string()
    } else {
        text.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_reads_pass_and_fail_from_what_libtest_prints() {
        let out = "running 3 tests\n\
            test a::ok_one ... ok\n\
            test a::bad ... FAILED\n\
            test a::skipped ... ignored\n\
            \n\
            failures:\n\
            \n\
            ---- a::bad stdout ----\n\
            thread 'a::bad' panicked at src/a.rs:3:5:\n\
            assertion failed\n\
            \n\
            failures:\n    a::bad\n";

        let r = results(out);

        assert_eq!(r.len(), 2);
        assert!(r[0].passed);
        assert!(!r[1].passed);
        assert!(r[1].message.contains("assertion failed"));
        assert!(!r[1].message.contains("failures:"));
    }

    #[test]
    fn a_failure_without_a_section_still_says_so() {
        let r = results("test a ... FAILED\n");

        assert_eq!(r[0].message, "the test failed");
    }

    #[test]
    fn it_lists_tests_and_ignores_benchmarks() {
        assert_eq!(
            listing("a::one: test\nb: benchmark\nc::two: test\n\n2 tests, 1 benchmarks\n"),
            vec!["a::one", "c::two"]
        );
    }

    #[test]
    fn it_finds_test_executables_by_the_kind_of_target() {
        let work = Path::new("/w");
        let msg = |kind: &str, name: &str, src: &str, test: bool, exe: &str| {
            format!(
                r#"{{"reason":"compiler-artifact","package_id":"path+file:///w#p@1.0.0","target":{{"kind":["{kind}"],"name":"{name}","src_path":"{src}"}},"profile":{{"test":{test}}},"executable":{exe}}}"#
            )
        };
        let out = [
            msg("lib", "p", "/w/src/lib.rs", true, "\"/t/p\""),
            msg("test", "pipe", "/w/tests/pipe.rs", true, "\"/t/pipe\""),
            msg("lib", "p", "/w/src/lib.rs", false, "null"),
            msg("bin", "p", "/w/src/main.rs", true, "\"/t/pm\""),
            msg(
                "custom-build",
                "build-script-build",
                "/w/build.rs",
                false,
                "null",
            ),
            "not json at all".to_string(),
        ]
        .join("\n");

        let targets = artifacts(&out, work);

        assert_eq!(
            targets.iter().map(|t| t.file.as_str()).collect::<Vec<_>>(),
            vec!["src/lib.rs", "tests/pipe.rs", "src/main.rs"]
        );
        assert_eq!(targets[0].select, vec!["--lib"]);
        assert_eq!(targets[1].select, vec!["--test", "pipe"]);
        assert_eq!(targets[2].select, vec!["--bin", "p"]);
    }

    #[test]
    fn only_the_compilers_errors_are_diagnostics() {
        let out = [
            r#"{"reason":"compiler-message","message":{"level":"warning","rendered":"warn\n"}}"#,
            r#"{"reason":"compiler-message","message":{"level":"error","rendered":"error[E0308]: mismatched\n"}}"#,
            r#"{"reason":"build-finished","success":false}"#,
        ]
        .join("\n");

        assert_eq!(diagnostics(&out), "error[E0308]: mismatched\n");
    }

    #[test]
    fn a_copy_leaves_out_what_cargo_built_and_what_git_keeps() {
        let dir = std::env::temp_dir().join(format!("kalku-copy-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let src = dir.join("src_tree");
        fs::create_dir_all(src.join("src")).unwrap();
        fs::create_dir_all(src.join("target/debug")).unwrap();
        fs::create_dir_all(src.join(".git")).unwrap();
        fs::write(src.join("src/lib.rs"), "x").unwrap();
        fs::write(src.join("target/debug/big"), "x").unwrap();
        fs::write(src.join(".git/HEAD"), "x").unwrap();
        let to = dir.join("copy");

        copy_tree(&src, &to, &to).unwrap();

        assert!(to.join("src/lib.rs").exists());
        assert!(!to.join("target").exists());
        assert!(!to.join(".git").exists());
        let _ = fs::remove_dir_all(&dir);
    }
}
