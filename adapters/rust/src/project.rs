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

use crate::cancel::{Stop, capture};
use crate::coverage::{Lines, Tools, parse_lcov};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
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
    /// Bring the reni's copy of the project up to date with the project.
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
    /// What the operations that follow look at to know they should stop; one
    /// that is stopped fails with `Interrupted`. A project that cannot be
    /// stopped ignores it.
    fn set_stop(&mut self, _stop: Option<Stop>) {}
    /// Build the project again, instrumented to say which lines run, apart from
    /// the ordinary build.
    fn instrument(&mut self, _tools: &Tools) -> io::Result<Build> {
        Err(io::ErrorKind::Unsupported.into())
    }
    /// Run one test alone on the instrumented build, and say which lines it ran.
    fn run_covered(&mut self, _target: &Target, _name: &str) -> io::Result<(Ran, Lines)> {
        Err(io::ErrorKind::Unsupported.into())
    }
    /// How a run of the suite here is unlike the project's own test command,
    /// a sentence each: what a test can tell, and nothing else.
    fn differences(&self) -> Vec<String> {
        Vec::new()
    }
}

pub struct Cargo {
    root: PathBuf,
    reni: PathBuf,
    work: PathBuf,
    worker: i64,
    target_dir: PathBuf,
    env: Vec<(String, String)>,
    stop: Option<Stop>,
    // What reads a profile, and the executables the profile is read against.
    tools: Option<Tools>,
    objects: Vec<PathBuf>,
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

// The variables of `INHERITED` that `get` has, and nothing else.
fn passed_on(get: impl Fn(&str) -> Option<String>) -> Vec<(String, String)> {
    INHERITED
        .iter()
        .filter_map(|k| get(k).map(|v| (k.to_string(), v)))
        .collect()
}

impl Cargo {
    pub fn new(root: PathBuf, reni: PathBuf, worker: i64, extra: Vec<(String, String)>) -> Self {
        let mut env = passed_on(|k| std::env::var(k).ok());
        env.extend(extra);
        Cargo {
            root,
            work: reni.join("work").join(worker.to_string()),
            worker,
            target_dir: reni.join("target").join(worker.to_string()),
            reni,
            env,
            stop: None,
            tools: None,
            objects: Vec::new(),
        }
    }

    // The lines the profiles in a directory say were run, read against the
    // instrumented executables.
    fn lines_of(&self, tools: &Tools, profiles: &Path) -> io::Result<Lines> {
        let raw: Vec<PathBuf> = fs::read_dir(profiles)?
            .filter_map(|e| e.ok().map(|e| e.path()))
            .filter(|p| p.extension().is_some_and(|x| x == "profraw"))
            .collect();
        if raw.is_empty() {
            return Ok(Lines::new());
        }
        let merged = profiles.join("merged.profdata");
        let mut merge = Command::new(&tools.profdata);
        merge
            .args(["merge", "-sparse"])
            .args(&raw)
            .arg("-o")
            .arg(&merged)
            .stdin(Stdio::null())
            .stderr(Stdio::inherit());
        if !capture(merge, self.stop.as_ref())?.status.success() {
            return Err(io::Error::other(
                "llvm-profdata could not merge the profiles",
            ));
        }
        let mut export = Command::new(&tools.cov);
        export
            .args(["export", "--format=lcov"])
            .arg(format!("--instr-profile={}", merged.display()));
        for (at, object) in self.objects.iter().enumerate() {
            if at > 0 {
                export.arg("-object");
            }
            export.arg(object);
        }
        export.stdin(Stdio::null()).stderr(Stdio::inherit());
        let out = capture(export, self.stop.as_ref())?;
        if !out.status.success() {
            return Err(io::Error::other("llvm-cov could not read the profile"));
        }
        let work = fs::canonicalize(&self.work).unwrap_or_else(|_| self.work.clone());
        Ok(parse_lcov(&String::from_utf8_lossy(&out.stdout), &work))
    }

    // Cargo on the instrumented build: its own target directory, and the flag
    // that makes every binary count the lines it runs.
    fn instrumented(&self) -> Command {
        let mut c = self.cargo();
        let flags = self
            .env
            .iter()
            .find(|(k, _)| k == "RUSTFLAGS")
            .map(|(_, v)| format!("{v} "))
            .unwrap_or_default();
        c.env("RUSTFLAGS", format!("{flags}-C instrument-coverage"))
            .env(
                "CARGO_TARGET_DIR",
                self.reni
                    .join("target")
                    .join(format!("{}-cov", self.worker)),
            );
        c
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
        sync_tree(&self.root, &self.work, &self.reni)
    }

    fn build(&mut self) -> io::Result<Build> {
        let mut command = self.cargo();
        command.args(["test", "--no-run", "--workspace", "--message-format=json"]);
        let out = capture(command, self.stop.as_ref())?;
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
        let mut command = self.cargo();
        command
            .args(["test", "-p", &target.package])
            .args(&target.select)
            .args(["--", "--list"]);
        let out = capture(command, self.stop.as_ref())?;
        let all = listing(&String::from_utf8_lossy(&out.stdout));

        // `--list` names a test the project marked `#[ignore]` like any other,
        // and run alone it answers `ignored`: neither a pass nor a failure.
        // It is not part of the suite the project runs, so not of this one.
        let mut command = self.cargo();
        command
            .args(["test", "-p", &target.package])
            .args(&target.select)
            .args(["--", "--list", "--ignored"]);
        let out = capture(command, self.stop.as_ref())?;
        let ignored = listing(&String::from_utf8_lossy(&out.stdout));
        Ok(all.into_iter().filter(|t| !ignored.contains(t)).collect())
    }

    fn run(&mut self, target: &Target, names: &[String]) -> io::Result<Ran> {
        let mut c = self.cargo();
        c.args(["test", "-p", &target.package]).args(&target.select);
        if !names.is_empty() {
            c.arg("--").arg("--exact").args(names);
        }
        let out = capture(c, self.stop.as_ref())?;
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

    fn set_stop(&mut self, stop: Option<Stop>) {
        self.stop = stop;
    }

    fn instrument(&mut self, tools: &Tools) -> io::Result<Build> {
        let mut command = self.instrumented();
        command.args(["test", "--no-run", "--workspace", "--message-format=json"]);
        let out = capture(command, self.stop.as_ref())?;
        let stdout = String::from_utf8_lossy(&out.stdout);
        if !out.status.success() {
            let errors = diagnostics(&stdout);
            return Ok(Build::Failed(if errors.is_empty() {
                "cargo could not build the project with coverage".to_string()
            } else {
                errors
            }));
        }
        self.objects = executables(&stdout);
        self.tools = Some(tools.clone());
        Ok(Build::Built(artifacts(&stdout, &self.work)))
    }

    fn run_covered(&mut self, target: &Target, name: &str) -> io::Result<(Ran, Lines)> {
        let Some(tools) = self.tools.clone() else {
            return Err(io::Error::other("the project was not built with coverage"));
        };
        let profiles = self.reni.join("cov").join(self.worker.to_string());
        if profiles.exists() {
            fs::remove_dir_all(&profiles)?;
        }
        fs::create_dir_all(&profiles)?;
        let mut command = self.instrumented();
        command
            .env("LLVM_PROFILE_FILE", profiles.join("%p-%m.profraw"))
            .args(["test", "-p", &target.package])
            .args(&target.select)
            .args(["--", "--exact", name]);
        let out = capture(command, self.stop.as_ref())?;
        let ran = Ran {
            results: results(&String::from_utf8_lossy(&out.stdout)),
            success: out.status.success(),
        };
        let lines = self.lines_of(&tools, &profiles)?;
        Ok((ran, lines))
    }

    // Unlike `cargo test` at the root of the project. A suite that is green
    // there and red here is red for one of these.
    fn differences(&self) -> Vec<String> {
        let build = match self.tools {
            Some(_) => self
                .reni
                .join("target")
                .join(format!("{}-cov", self.worker)),
            None => self.target_dir.clone(),
        };
        let passed_on: Vec<&str> = self.env.iter().map(|(k, _)| k.as_str()).collect();
        vec![
            format!(
                "it ran in a copy of the project kept in the reni, {}: what the copy leaves \
                 out is not there, `.git` and whatever is named `target`",
                self.work.display()
            ),
            format!(
                "the build is in {} (CARGO_TARGET_DIR), not in `target`: a test that looks \
                 in `target` for what a build leaves does not find it",
                build.display()
            ),
            "each test ran alone, in a run of its test executable for it and no other \
             (`-- --exact`), one after another: what the tests of an executable share when \
             they run together is not shared"
                .to_string(),
            "in a workspace the tests of every package ran (`--workspace`), where `cargo \
             test` at the root takes the root package or the default members"
                .to_string(),
            format!(
                "the tests see none of your environment but {}: any other variable set for \
                 them in your shell is not there",
                passed_on.join(", ")
            ),
        ]
    }
}

// Put the project's files in `to`, without what Cargo built or what git
// keeps; `reni` is skipped in case it is inside the project.
//
// Cargo decides what to rebuild by file times, and the reni outlives a
// run. A copy that kept the original's times beside artifacts built from
// an earlier version of the same file would be trusted as up to date, and
// a binary built from other code would be measured. So a file whose content
// differs is written anew, with a time of now; one that is the same is left
// alone, so what was built from it stays valid; and what the project no
// longer has is removed.
fn sync_tree(from: &Path, to: &Path, reni: &Path) -> io::Result<()> {
    fs::create_dir_all(to)?;
    let mut wanted = HashSet::new();
    for entry in fs::read_dir(from)? {
        let entry = entry?;
        let name = entry.file_name();
        let path = entry.path();
        if name == "target" || name == ".git" || path == reni {
            continue;
        }
        let kind = entry.file_type()?;
        let dest = to.join(&name);
        wanted.insert(name);
        if kind.is_dir() {
            if !is_dir(&dest) {
                remove_any(&dest)?;
            }
            sync_tree(&path, &dest, reni)?;
        } else if kind.is_symlink() {
            #[cfg(unix)]
            {
                let target = fs::read_link(&path)?;
                if fs::read_link(&dest).ok().as_ref() != Some(&target) {
                    remove_any(&dest)?;
                    std::os::unix::fs::symlink(target, &dest)?;
                }
            }
        } else {
            let bytes = fs::read(&path)?;
            if fs::read(&dest).ok().as_deref() != Some(bytes.as_slice()) {
                if fs::symlink_metadata(&dest).is_ok_and(|m| !m.is_file()) {
                    remove_any(&dest)?;
                }
                fs::write(&dest, bytes)?;
                fs::set_permissions(&dest, fs::metadata(&path)?.permissions())?;
            }
        }
    }
    for entry in fs::read_dir(to)? {
        let entry = entry?;
        if !wanted.contains(&entry.file_name()) {
            remove_any(&entry.path())?;
        }
    }
    Ok(())
}

fn is_dir(path: &Path) -> bool {
    fs::symlink_metadata(path).is_ok_and(|m| m.is_dir())
}

// A file, a link or a directory, whichever it is; nothing at all is fine.
fn remove_any(path: &Path) -> io::Result<()> {
    match fs::symlink_metadata(path) {
        Ok(m) if m.is_dir() => fs::remove_dir_all(path),
        Ok(_) => fs::remove_file(path),
        Err(_) => Ok(()),
    }
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

/// Every executable cargo built, in the order it said so.
pub fn executables(stdout: &str) -> Vec<PathBuf> {
    let mut seen = HashSet::new();
    stdout
        .lines()
        .filter_map(|l| serde_json::from_str::<Value>(l).ok())
        .filter(|m| m["reason"] == "compiler-artifact")
        .filter_map(|m| m["executable"].as_str().map(PathBuf::from))
        .filter(|p| seen.insert(p.clone()))
        .collect()
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
    fn every_executable_cargo_built_is_listed_once_tests_or_not() {
        let artifact = |exe: &str, test: bool| {
            format!(
                r#"{{"reason":"compiler-artifact","executable":{exe},"profile":{{"test":{test}}}}}"#
            )
        };
        let out = [
            artifact("\"/t/lib-test\"", true),
            artifact("\"/t/bin\"", false),
            artifact("\"/t/lib-test\"", true),
            artifact("null", false),
            r#"{"reason":"build-finished","executable":"/t/not-an-artifact"}"#.to_string(),
            "not json".to_string(),
        ]
        .join("\n");

        assert_eq!(
            executables(&out),
            [PathBuf::from("/t/lib-test"), PathBuf::from("/t/bin")]
        );
    }

    #[test]
    fn several_errors_are_told_one_after_the_other() {
        let out = [
            r#"{"reason":"compiler-message","message":{"level":"error","rendered":"first\n"}}"#,
            r#"{"reason":"compiler-message","message":{"level":"error","rendered":"second\n"}}"#,
        ]
        .join("\n");

        assert_eq!(diagnostics(&out), "first\n\nsecond\n");
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

        sync_tree(&src, &to, &to.join("reni")).unwrap();

        assert!(to.join("src/lib.rs").exists());
        assert!(!to.join("target").exists());
        assert!(!to.join(".git").exists());
        let _ = fs::remove_dir_all(&dir);
    }

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("kalku-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn set_time(path: &Path, seconds: u64) {
        let at = std::time::UNIX_EPOCH + std::time::Duration::from_secs(seconds);
        fs::File::options()
            .write(true)
            .open(path)
            .unwrap()
            .set_modified(at)
            .unwrap();
    }

    fn modified(path: &Path) -> std::time::SystemTime {
        fs::metadata(path).unwrap().modified().unwrap()
    }

    // The bug this prevents: the original's old time on a changed file,
    // beside artifacts built from the earlier content, is a stale build.
    #[test]
    fn a_file_that_changed_is_written_with_a_time_of_now_whatever_the_original_says() {
        let dir = scratch("changed");
        let (src, to) = (dir.join("src"), dir.join("to"));
        fs::create_dir_all(&src).unwrap();
        fs::write(src.join("a.rs"), "one").unwrap();
        sync_tree(&src, &to, &dir.join("reni")).unwrap();

        fs::write(src.join("a.rs"), "two").unwrap();
        set_time(&src.join("a.rs"), 1_000);
        sync_tree(&src, &to, &dir.join("reni")).unwrap();

        assert_eq!(fs::read_to_string(to.join("a.rs")).unwrap(), "two");
        let age = std::time::SystemTime::now()
            .duration_since(modified(&to.join("a.rs")))
            .unwrap();
        assert!(
            age.as_secs() < 3600,
            "the copy kept the original's old time"
        );
        let _ = fs::remove_dir_all(&dir);
    }

    // What was built from a file stays valid while the file does not change.
    #[test]
    fn a_file_that_did_not_change_is_left_alone() {
        let dir = scratch("same");
        let (src, to) = (dir.join("src"), dir.join("to"));
        fs::create_dir_all(&src).unwrap();
        fs::write(src.join("a.rs"), "one").unwrap();
        sync_tree(&src, &to, &dir.join("reni")).unwrap();
        set_time(&to.join("a.rs"), 5_000);

        sync_tree(&src, &to, &dir.join("reni")).unwrap();

        assert_eq!(
            modified(&to.join("a.rs")),
            std::time::UNIX_EPOCH + std::time::Duration::from_secs(5_000)
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn what_the_project_no_longer_has_is_removed_from_the_copy() {
        let dir = scratch("gone");
        let (src, to) = (dir.join("src"), dir.join("to"));
        fs::create_dir_all(src.join("old")).unwrap();
        fs::write(src.join("old/a.rs"), "x").unwrap();
        fs::write(src.join("b.rs"), "x").unwrap();
        sync_tree(&src, &to, &dir.join("reni")).unwrap();

        fs::remove_dir_all(src.join("old")).unwrap();
        fs::remove_file(src.join("b.rs")).unwrap();
        sync_tree(&src, &to, &dir.join("reni")).unwrap();

        assert!(!to.join("old").exists());
        assert!(!to.join("b.rs").exists());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn only_what_a_build_needs_is_passed_on() {
        let everything = |k: &str| Some(format!("value of {k}"));
        let passed: Vec<String> = passed_on(everything).into_iter().map(|(k, _)| k).collect();

        assert_eq!(
            passed,
            [
                "PATH",
                "HOME",
                "USER",
                "TMPDIR",
                "CARGO_HOME",
                "RUSTUP_HOME",
                "RUSTUP_TOOLCHAIN",
                "RUSTFLAGS",
                "LANG"
            ]
        );
        // A secret of the caller's is not something a test run was given.
        let only_secret = |k: &str| (k == "AWS_SECRET_ACCESS_KEY").then(|| "x".to_string());
        assert!(passed_on(only_secret).is_empty());
        assert_eq!(
            passed_on(|k| (k == "RUSTUP_TOOLCHAIN").then(|| "1.85".to_string())),
            [("RUSTUP_TOOLCHAIN".to_string(), "1.85".to_string())]
        );
    }

    #[test]
    fn it_says_where_it_ran_where_it_built_and_what_of_the_environment_it_kept() {
        let mut cargo = Cargo::new(
            PathBuf::from("/root"),
            PathBuf::from("/reni"),
            3,
            vec![("EXTRA".to_string(), "1".to_string())],
        );
        cargo.env.retain(|(k, _)| k == "EXTRA");

        let said = cargo.differences();

        assert_eq!(said.len(), 5);
        assert!(said[0].contains("a copy of the project kept in the reni, /reni/work/3:"));
        assert!(said[0].contains("`.git`") && said[0].contains("`target`"));
        assert!(said[1].starts_with("the build is in /reni/target/3 (CARGO_TARGET_DIR)"));
        assert!(said[2].starts_with("each test ran alone"));
        assert!(said[3].contains("`--workspace`"));
        assert!(said[4].contains("none of your environment but EXTRA:"));
    }

    #[test]
    fn the_build_it_names_is_the_instrumented_one_when_that_is_what_ran() {
        let mut cargo = Cargo::new(PathBuf::from("/root"), PathBuf::from("/reni"), 3, vec![]);
        cargo.tools = Some(Tools {
            profdata: PathBuf::from("llvm-profdata"),
            cov: PathBuf::from("llvm-cov"),
        });

        assert!(cargo.differences()[1].starts_with("the build is in /reni/target/3-cov "));
    }

    #[test]
    fn cargo_runs_in_the_copy_with_its_own_target_directory_and_no_colour() {
        let cargo = Cargo::new(
            PathBuf::from("/root"),
            PathBuf::from("/reni"),
            3,
            vec![("EXTRA".to_string(), "1".to_string())],
        );

        let command = cargo.cargo();
        let envs: HashMap<_, _> = command
            .get_envs()
            .map(|(k, v)| {
                (
                    k.to_string_lossy().into_owned(),
                    v.map(|v| v.to_string_lossy().into_owned()),
                )
            })
            .collect();

        assert_eq!(command.get_current_dir(), Some(Path::new("/reni/work/3")));
        assert_eq!(envs["CARGO_TARGET_DIR"].as_deref(), Some("/reni/target/3"));
        assert_eq!(envs["CARGO_TERM_COLOR"].as_deref(), Some("never"));
        assert_eq!(envs["EXTRA"].as_deref(), Some("1"));
    }

    #[test]
    fn a_non_test_artifact_with_an_executable_is_not_a_test_target() {
        let work = Path::new("/w");
        let artifact = |reason: &str, test: bool| {
            format!(
                r#"{{"reason":"{reason}","package_id":"path+file:///w#p@1.0.0","target":{{"kind":["bin"],"name":"p","src_path":"/w/src/main.rs"}},"profile":{{"test":{test}}},"executable":"/t/p"}}"#
            )
        };

        // The binary itself is built, with an executable, but it is not a test.
        assert!(artifacts(&artifact("compiler-artifact", false), work).is_empty());
        // Something that is not an artifact never names a target.
        assert!(artifacts(&artifact("build-finished", true), work).is_empty());
        assert_eq!(
            artifacts(&artifact("compiler-artifact", true), work).len(),
            1
        );
    }

    #[test]
    fn a_failure_message_keeps_its_lines_and_stops_at_the_next_section() {
        let out = "---- a stdout ----\nfirst\nsecond\n\nfailures:\n    a\n";
        assert_eq!(failure_message(out, "a"), "first\nsecond");

        let two = "---- a stdout ----\nfirst\n---- b stdout ----\nother\n";
        assert_eq!(failure_message(two, "a"), "first");
        assert_eq!(failure_message(two, "b"), "other");
    }

    // Where the project has a link, the copy has the same link, and a new
    // target is followed.
    #[cfg(unix)]
    #[test]
    fn a_link_is_copied_and_follows_its_target_when_that_changes() {
        let dir = scratch("links");
        let (src, to) = (dir.join("src"), dir.join("to"));
        fs::create_dir_all(&src).unwrap();
        std::os::unix::fs::symlink("one", src.join("l")).unwrap();
        sync_tree(&src, &to, &dir.join("reni")).unwrap();
        assert_eq!(fs::read_link(to.join("l")).unwrap(), Path::new("one"));

        fs::remove_file(src.join("l")).unwrap();
        std::os::unix::fs::symlink("two", src.join("l")).unwrap();
        sync_tree(&src, &to, &dir.join("reni")).unwrap();

        assert_eq!(fs::read_link(to.join("l")).unwrap(), Path::new("two"));
        let _ = fs::remove_dir_all(&dir);
    }

    // A path that was a file and is now a directory, and the other way.
    #[test]
    fn a_file_becomes_a_directory_in_the_copy_and_back() {
        let dir = scratch("kinds");
        let (src, to) = (dir.join("src"), dir.join("to"));
        fs::create_dir_all(&src).unwrap();
        fs::write(src.join("x"), "file").unwrap();
        sync_tree(&src, &to, &dir.join("reni")).unwrap();

        fs::remove_file(src.join("x")).unwrap();
        fs::create_dir(src.join("x")).unwrap();
        fs::write(src.join("x/inner"), "in").unwrap();
        sync_tree(&src, &to, &dir.join("reni")).unwrap();
        assert_eq!(fs::read_to_string(to.join("x/inner")).unwrap(), "in");

        fs::remove_dir_all(src.join("x")).unwrap();
        fs::write(src.join("x"), "file again").unwrap();
        sync_tree(&src, &to, &dir.join("reni")).unwrap();
        assert_eq!(fs::read_to_string(to.join("x")).unwrap(), "file again");
        let _ = fs::remove_dir_all(&dir);
    }
}
