//! Which tests reach which lines, from LLVM's own instrumentation.
//!
//! A project is built with `-C instrument-coverage`, each test is run alone
//! and leaves a raw profile, and LLVM turns that into the lines it executed.
//! The tools that do it ship as a rustup component (`llvm-tools`), so they
//! are looked for in the toolchain's own directory and the capability is only
//! announced when they are there: a kalku that claimed it and could not do it
//! would report every wekufe as covered by no test.

use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};

/// The two programs that read a profile.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Tools {
    pub profdata: PathBuf,
    pub cov: PathBuf,
}

/// The tools in a toolchain's directory, or `None` when it lacks either.
pub fn find_tools(sysroot: &Path, host: &str) -> Option<Tools> {
    let bin = sysroot.join("lib").join("rustlib").join(host).join("bin");
    let profdata = bin.join("llvm-profdata");
    let cov = bin.join("llvm-cov");
    (profdata.is_file() && cov.is_file()).then_some(Tools { profdata, cov })
}

/// The lines one test executed, by file.
pub type Lines = BTreeMap<String, BTreeSet<u32>>;

/// The lines with a count above zero in LLVM's `lcov` export, by file
/// relative to `work`. Files outside `work` (the standard library, the
/// registry's crates) are not the project's and are left out.
pub fn parse_lcov(lcov: &str, work: &Path) -> Lines {
    let mut lines = Lines::new();
    let mut file: Option<String> = None;
    for line in lcov.lines() {
        if let Some(path) = line.strip_prefix("SF:") {
            let path = Path::new(path);
            // A path LLVM kept relative is already relative to the project.
            file = if path.is_relative() {
                Some(path.to_string_lossy().into_owned())
            } else {
                path.strip_prefix(work)
                    .ok()
                    .map(|p| p.to_string_lossy().into_owned())
            };
        } else if let (Some(file), Some(hit)) = (&file, line.strip_prefix("DA:")) {
            let mut parts = hit.split(',');
            let (number, count) = (parts.next(), parts.next());
            if let (Some(number), Some(count)) = (
                number.and_then(|n| n.parse::<u32>().ok()),
                count.and_then(|c| c.parse::<u64>().ok()),
            ) {
                if count > 0 {
                    lines.entry(file.clone()).or_default().insert(number);
                }
            }
        } else if line == "end_of_record" {
            file = None;
        }
    }
    lines
}

/// One entry of the coverage map: a line and every test that reached it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub file: String,
    pub line: u32,
    pub tests: Vec<String>,
}

/// Per-test lines turned around: for each line, the tests that reached it,
/// in the order the tests were given.
pub fn invert(per_test: &[(String, Lines)]) -> Vec<Entry> {
    let mut reached: BTreeMap<(&str, u32), Vec<&str>> = BTreeMap::new();
    for (test, lines) in per_test {
        for (file, numbers) in lines {
            for number in numbers {
                reached
                    .entry((file.as_str(), *number))
                    .or_default()
                    .push(test.as_str());
            }
        }
    }
    reached
        .into_iter()
        .map(|((file, line), tests)| Entry {
            file: file.to_string(),
            line,
            tests: tests.into_iter().map(str::to_string).collect(),
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const LCOV: &str = "TN:\nSF:/w/src/lib.rs\nFN:1,f\nFNDA:3,f\nDA:1,3\nDA:2,0\nDA:3,12\nBRDA:1,0,0,1\nend_of_record\n\
        SF:/rustc/abc/library/core/src/ops.rs\nDA:9,4\nend_of_record\n\
        SF:/w/crates/a/src/x.rs\nDA:5,1\nDA:garbled\nDA:6,-\nend_of_record\n";

    #[test]
    fn it_reads_the_lines_that_ran_in_the_projects_own_files() {
        let lines = parse_lcov(LCOV, Path::new("/w"));

        assert_eq!(lines["src/lib.rs"], BTreeSet::from([1, 3]));
        assert_eq!(lines["crates/a/src/x.rs"], BTreeSet::from([5]));
        // The standard library is not the project's, and a line that did not
        // run is not a line a test reached.
        assert_eq!(lines.len(), 2);
    }

    #[test]
    fn a_data_line_outside_a_file_record_belongs_to_no_file() {
        assert!(
            parse_lcov(
                "DA:1,1\nSF:/w/a.rs\nend_of_record\nDA:2,2\n",
                Path::new("/w")
            )
            .is_empty()
        );
    }

    #[test]
    fn lines_are_turned_around_into_the_tests_that_reached_them() {
        let a = Lines::from([("a.rs".to_string(), BTreeSet::from([1, 2]))]);
        let b = Lines::from([
            ("a.rs".to_string(), BTreeSet::from([2])),
            ("b.rs".to_string(), BTreeSet::from([7])),
        ]);

        let entries = invert(&[("t1".into(), a), ("t2".into(), b)]);

        assert_eq!(
            entries,
            [
                Entry {
                    file: "a.rs".into(),
                    line: 1,
                    tests: vec!["t1".into()]
                },
                Entry {
                    file: "a.rs".into(),
                    line: 2,
                    tests: vec!["t1".into(), "t2".into()]
                },
                Entry {
                    file: "b.rs".into(),
                    line: 7,
                    tests: vec!["t2".into()]
                },
            ]
        );
        assert!(invert(&[]).is_empty());
    }

    #[test]
    fn the_tools_are_found_only_when_both_are_in_the_toolchain() {
        let dir = std::env::temp_dir().join(format!("kalku-tools-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let bin = dir.join("lib/rustlib/aarch64-apple-darwin/bin");
        std::fs::create_dir_all(&bin).unwrap();
        assert_eq!(find_tools(&dir, "aarch64-apple-darwin"), None);

        std::fs::write(bin.join("llvm-profdata"), "").unwrap();
        assert_eq!(find_tools(&dir, "aarch64-apple-darwin"), None);

        std::fs::write(bin.join("llvm-cov"), "").unwrap();
        let tools = find_tools(&dir, "aarch64-apple-darwin").unwrap();
        assert_eq!(tools.profdata, bin.join("llvm-profdata"));
        assert_eq!(tools.cov, bin.join("llvm-cov"));
        assert_eq!(find_tools(&dir, "x86_64-unknown-linux-gnu"), None);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
