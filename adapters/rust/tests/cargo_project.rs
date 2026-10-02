//! The project in the reni, against real Cargo: what it builds, lists and
//! runs. The pure parts of reading Cargo's output are tested beside the
//! code; this is what only a real Cargo can say.

use kalku_rust::project::{Build, Cargo, Project, Target};
use std::fs;
use std::path::PathBuf;

struct Dir(PathBuf);

impl Dir {
    fn new(name: &str) -> Dir {
        let dir = std::env::temp_dir().join(format!("kalku_cargo_{}_{name}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        Dir(dir)
    }

    fn file(&self, path: &str, text: &str) {
        let full = self.0.join(path);
        fs::create_dir_all(full.parent().unwrap()).unwrap();
        fs::write(full, text).unwrap();
    }

    fn project(&self) -> Cargo {
        Cargo::new(self.0.join("proj"), self.0.join("reni"), 0, vec![])
    }
}

impl Drop for Dir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

const PACKAGE: &str = "[package]\nname = \"p\"\nversion = \"0.1.0\"\nedition = \"2024\"\n";

fn built(project: &mut Cargo) -> Vec<Target> {
    project.copy().unwrap();
    match project.build().unwrap() {
        Build::Built(targets) => targets,
        Build::Failed(why) => panic!("the project did not build: {why}"),
    }
}

// A name that is only part of another one, so a run that matched by
// substring would run both.
const TESTS: &str =
    "#[cfg(test)]\nmod t {\n    #[test]\n    fn a() {}\n    #[test]\n    fn ab() {}\n}\n";

#[test]
fn it_lists_and_runs_exactly_the_tests_it_is_asked_for() {
    let dir = Dir::new("exact");
    dir.file("proj/Cargo.toml", PACKAGE);
    dir.file("proj/src/lib.rs", TESTS);
    let mut project = dir.project();
    let targets = built(&mut project);
    assert_eq!(targets.len(), 1);
    assert_eq!(targets[0].file, "src/lib.rs");

    let mut listed = project.list(&targets[0]).unwrap();
    listed.sort();
    assert_eq!(listed, ["t::a", "t::ab"]);

    let one = project.run(&targets[0], &["t::a".to_string()]).unwrap();
    assert!(one.success);
    assert_eq!(
        one.results
            .iter()
            .map(|r| r.name.as_str())
            .collect::<Vec<_>>(),
        ["t::a"]
    );

    let all = project.run(&targets[0], &[]).unwrap();
    assert_eq!(all.results.len(), 2);
}

#[test]
fn a_failing_test_is_a_result_not_an_error() {
    let dir = Dir::new("failing");
    dir.file("proj/Cargo.toml", PACKAGE);
    dir.file(
        "proj/src/lib.rs",
        "#[cfg(test)]\nmod t {\n    #[test]\n    fn bad() { assert_eq!(1, 2, \"boom\"); }\n}\n",
    );
    let mut project = dir.project();
    let targets = built(&mut project);

    let ran = project.run(&targets[0], &["t::bad".to_string()]).unwrap();

    assert!(!ran.success);
    assert!(!ran.results[0].passed);
    assert!(ran.results[0].message.contains("boom"));
}

// `--workspace`: a virtual workspace whose default members are only one of
// its packages would otherwise build only that one.
#[test]
fn every_package_of_a_workspace_is_built() {
    let dir = Dir::new("workspace");
    dir.file(
        "proj/Cargo.toml",
        "[workspace]\nmembers = [\"a\", \"b\"]\ndefault-members = [\"a\"]\nresolver = \"3\"\n",
    );
    for member in ["a", "b"] {
        dir.file(
            &format!("proj/{member}/Cargo.toml"),
            &format!("[package]\nname = \"{member}\"\nversion = \"0.1.0\"\nedition = \"2024\"\n"),
        );
        dir.file(&format!("proj/{member}/src/lib.rs"), TESTS);
    }
    let mut project = dir.project();

    let mut files: Vec<String> = built(&mut project).into_iter().map(|t| t.file).collect();
    files.sort();

    assert_eq!(files, ["a/src/lib.rs", "b/src/lib.rs"]);
}

#[test]
fn a_compile_error_is_a_failed_build_with_the_compilers_words() {
    let dir = Dir::new("compile");
    dir.file("proj/Cargo.toml", PACKAGE);
    dir.file("proj/src/lib.rs", "pub fn f() -> i32 { \"not an int\" }\n");
    let mut project = dir.project();
    project.copy().unwrap();

    match project.build().unwrap() {
        Build::Failed(why) => assert!(why.contains("mismatched types"), "{why}"),
        Build::Built(_) => panic!("a project that does not compile was built"),
    }
}

// Cargo can fail without a compiler error to quote: a manifest it cannot
// read, say. The build still failed, and says so.
#[test]
fn a_build_that_fails_without_a_diagnostic_still_says_it_failed() {
    let dir = Dir::new("manifest");
    dir.file("proj/Cargo.toml", "this is not toml [");
    dir.file("proj/src/lib.rs", "");
    let mut project = dir.project();
    project.copy().unwrap();

    match project.build().unwrap() {
        Build::Failed(why) => assert_eq!(why, "cargo could not build the project"),
        Build::Built(_) => panic!("a project with no manifest was built"),
    }
}

// The LLVM tools are a rustup component. Where they are not, there is
// nothing to test and the kalku does not claim coverage either; on CI they
// are installed, and a missing one is a failure rather than a skip.
fn tools() -> Option<kalku_rust::coverage::Tools> {
    let out = std::process::Command::new("rustc")
        .args(["--print", "sysroot"])
        .output()
        .unwrap();
    let sysroot = String::from_utf8(out.stdout).unwrap();
    let host = std::process::Command::new("rustc")
        .arg("-vV")
        .output()
        .unwrap()
        .stdout;
    let host = String::from_utf8(host)
        .unwrap()
        .lines()
        .find_map(|l| l.strip_prefix("host: ").map(str::to_string))
        .unwrap();
    let found = kalku_rust::coverage::find_tools(std::path::Path::new(sysroot.trim()), &host);
    assert!(
        found.is_some() || std::env::var_os("CI").is_none(),
        "CI must have the llvm-tools component"
    );
    found
}

#[test]
fn a_test_says_which_lines_of_the_project_it_ran() {
    let Some(tools) = tools() else { return };
    let dir = Dir::new("coverage");
    dir.file("proj/Cargo.toml", PACKAGE);
    dir.file(
        "proj/src/lib.rs",
        "pub fn used() -> i32 {\n    1\n}\n\npub fn unused() -> i32 {\n    2\n}\n\n#[cfg(test)]\nmod t {\n    #[test]\n    fn calls_used() {\n        assert_eq!(super::used(), 1);\n    }\n    #[test]\n    fn calls_nothing() {}\n}\n",
    );
    let mut project = dir.project();
    project.copy().unwrap();
    let targets = match project.instrument(&tools).unwrap() {
        Build::Built(targets) => targets,
        Build::Failed(why) => panic!("{why}"),
    };

    let (ran, calling) = project.run_covered(&targets[0], "t::calls_used").unwrap();
    let (_, idle) = project
        .run_covered(&targets[0], "t::calls_nothing")
        .unwrap();

    assert!(ran.success);
    let reached = &calling["src/lib.rs"];
    assert!(reached.contains(&2), "the body of `used` ran: {reached:?}");
    assert!(
        !reached.contains(&6),
        "the body of `unused` did not: {reached:?}"
    );
    assert!(
        !idle.get("src/lib.rs").is_some_and(|l| l.contains(&2)),
        "a test that calls nothing reached the body of `used`"
    );
}
