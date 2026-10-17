//! What the tests of a package run, as one fingerprint per package.
//!
//! A test executable is compiled from its package and from everything the
//! package depends on, and generics and macros are expanded where they are
//! used, so the files a test's coverage names are not enough. The package
//! goes in by every file under its directory, which is also what its tests
//! read; so does every package of the copy it depends on, directly or not,
//! by any kind of dependency. Packages from a registry or from git go in by
//! `Cargo.lock`, which fixes their versions, and the toolchain by what
//! `rustc -vV` says of itself. The manifests and Cargo's own configuration
//! are files of the root, which go in too, and so do the variables the tests
//! are given, but for the ones that only say where things are on a machine.
//!
//! Paths go in relative to the copy, so two checkouts of a project agree.
//! What a test reads from outside its package's directory and the root's own
//! files is not in it.

use serde_json::Value;
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};

// The files of the root that change how Cargo builds every package.
const ROOT_FILES: [&str; 6] = [
    "Cargo.toml",
    "Cargo.lock",
    ".cargo/config.toml",
    ".cargo/config",
    "rust-toolchain.toml",
    "rust-toolchain",
];

// Variables that say where things are, not what a test sees in them.
const LOCATIONS: [&str; 7] = [
    "PATH",
    "HOME",
    "USER",
    "TMPDIR",
    "CARGO_HOME",
    "RUSTUP_HOME",
    "LANG",
];

/// What every package's fingerprint starts from: the kalku, the toolchain,
/// the variables the tests are given, and the root's files.
pub fn base(kalku: &str, rustc: &str, env: &[(String, String)], work: &Path) -> String {
    let mut h = Sha256::new();
    h.update(format!("kalku {kalku}\n{rustc}\n"));
    let given: BTreeMap<&str, &str> = env
        .iter()
        .filter(|(k, _)| !LOCATIONS.contains(&k.as_str()))
        .map(|(k, v)| (k.as_str(), v.as_str()))
        .collect();
    for (k, v) in given {
        h.update(format!("{k}={v}\n"));
    }
    for f in ROOT_FILES {
        h.update(format!("{f} {}\n", file_hash(&work.join(f))));
    }
    hex(&h.finalize())
}

/// The fingerprint of each package of the workspace, by its id, from what
/// `cargo metadata --format-version 1` says of the copy at `work`.
pub fn fingerprints(metadata: &Value, work: &Path, base: &str) -> HashMap<String, String> {
    // The packages of the copy, by id, and the directory each is in.
    let mut local: HashMap<&str, PathBuf> = HashMap::new();
    for p in metadata["packages"].as_array().into_iter().flatten() {
        let (Some(id), Some(manifest)) = (p["id"].as_str(), p["manifest_path"].as_str()) else {
            continue;
        };
        if p["source"].is_null() {
            if let Some(dir) = Path::new(manifest).parent() {
                local.insert(id, dir.to_path_buf());
            }
        }
    }
    let roots: HashSet<PathBuf> = local.values().cloned().collect();
    let mut deps: HashMap<&str, Vec<&str>> = HashMap::new();
    for node in metadata["resolve"]["nodes"]
        .as_array()
        .into_iter()
        .flatten()
    {
        let Some(id) = node["id"].as_str() else {
            continue;
        };
        let on = node["deps"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(|d| d["pkg"].as_str())
            .collect();
        deps.insert(id, on);
    }

    let mut own: HashMap<&str, String> = HashMap::new();
    let mut out = HashMap::new();
    for member in metadata["workspace_members"]
        .as_array()
        .into_iter()
        .flatten()
    {
        let Some(member) = member.as_str() else {
            continue;
        };
        if !local.contains_key(member) {
            continue;
        }
        // Every package of the copy the member's tests are built from.
        let mut seen = BTreeSet::new();
        let mut todo = vec![member];
        while let Some(id) = todo.pop() {
            if !seen.insert(id) {
                continue;
            }
            todo.extend(deps.get(id).into_iter().flatten().copied());
        }
        let mut parts: Vec<(String, String)> = Vec::new();
        for id in seen {
            let Some(dir) = local.get(id) else {
                continue;
            };
            let print = own
                .entry(id)
                .or_insert_with(|| tree_hash(dir, &roots))
                .clone();
            parts.push((relative(dir, work), print));
        }
        parts.sort();
        let mut h = Sha256::new();
        h.update(base);
        for (dir, print) in parts {
            h.update(format!("\n{dir} {print}"));
        }
        out.insert(member.to_string(), hex(&h.finalize())[..24].to_string());
    }
    out
}

// Directories no test reads: what Cargo built, what git keeps, and what kalku
// keeps of its own runs, which changes with every run.
const LEFT_OUT: [&str; 3] = ["target", ".git", ".kalku"];

// Every file under a package's directory, by its path from there and what is
// in it, leaving out the directories of `LEFT_OUT` and the packages below it,
// which go in by themselves.
fn tree_hash(dir: &Path, roots: &HashSet<PathBuf>) -> String {
    let mut files = Vec::new();
    walk(dir, dir, roots, &mut files);
    files.sort();
    let mut h = Sha256::new();
    for (name, what) in files {
        h.update(format!("{name} {what}\n"));
    }
    hex(&h.finalize())
}

fn walk(top: &Path, at: &Path, roots: &HashSet<PathBuf>, out: &mut Vec<(String, String)>) {
    let Ok(entries) = fs::read_dir(at) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let name = entry.file_name();
        let Ok(kind) = entry.file_type() else {
            continue;
        };
        let rel = path
            .strip_prefix(top)
            .unwrap_or(&path)
            .to_string_lossy()
            .replace('\\', "/");
        if kind.is_symlink() {
            let target = fs::read_link(&path).unwrap_or_default();
            out.push((rel, format!("-> {}", target.display())));
        } else if kind.is_dir() {
            if LEFT_OUT.iter().any(|l| name == *l) || roots.contains(&path) {
                continue;
            }
            walk(top, &path, roots, out);
        } else {
            out.push((rel, file_hash(&path)));
        }
    }
}

// A directory's path from the copy, or as it is when it is outside it.
fn relative(dir: &Path, work: &Path) -> String {
    let canonical = fs::canonicalize(work).unwrap_or_else(|_| work.to_path_buf());
    dir.strip_prefix(&canonical)
        .or_else(|_| dir.strip_prefix(work))
        .map(|p| p.to_string_lossy().replace('\\', "/"))
        .unwrap_or_else(|_| dir.to_string_lossy().into_owned())
}

fn file_hash(path: &Path) -> String {
    match fs::read(path) {
        Ok(bytes) => hex(&Sha256::digest(&bytes)),
        Err(_) => "absent".to_string(),
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("kalku-print-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn write(root: &Path, file: &str, text: &str) {
        let path = root.join(file);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, text).unwrap();
    }

    // A workspace of three packages: `app` depends on `core`, and `tool` on
    // nothing of the workspace; `serde` comes from a registry.
    fn workspace(name: &str) -> PathBuf {
        let root = scratch(name);
        write(&root, "Cargo.toml", "[workspace]");
        write(&root, "Cargo.lock", "lock");
        for p in ["app", "core", "tool"] {
            write(&root, &format!("{p}/Cargo.toml"), p);
            write(&root, &format!("{p}/src/lib.rs"), p);
        }
        root
    }

    fn metadata(root: &Path) -> Value {
        let package = |name: &str| {
            json!({"id": format!("{name}-id"), "source": null,
                "manifest_path": root.join(name).join("Cargo.toml")})
        };
        json!({
            "packages": [package("app"), package("core"), package("tool"),
                {"id": "serde-id", "source": "registry+https://github.com/rust-lang/crates.io-index",
                 "manifest_path": "/registry/serde/Cargo.toml"}],
            "workspace_members": ["app-id", "core-id", "tool-id"],
            "resolve": {"nodes": [
                {"id": "app-id", "deps": [{"pkg": "core-id"}, {"pkg": "serde-id"}]},
                {"id": "core-id", "deps": []},
                {"id": "tool-id", "deps": []},
                {"id": "serde-id", "deps": []}
            ]}
        })
    }

    fn prints(root: &Path) -> HashMap<String, String> {
        fingerprints(&metadata(root), root, "base")
    }

    #[test]
    fn every_member_has_a_fingerprint_and_a_registry_package_none() {
        let root = workspace("members");
        let said = prints(&root);
        let mut ids: Vec<_> = said.keys().cloned().collect();
        ids.sort();
        assert_eq!(ids, ["app-id", "core-id", "tool-id"]);
        assert!(said.values().all(|p| p.len() == 24));
    }

    #[test]
    fn a_change_reaches_the_package_and_every_package_built_from_it() {
        let root = workspace("reach");
        let before = prints(&root);
        write(&root, "core/src/lib.rs", "core, changed");
        let after = prints(&root);
        assert_ne!(before["core-id"], after["core-id"]);
        assert_ne!(before["app-id"], after["app-id"]);
        assert_eq!(before["tool-id"], after["tool-id"]);
    }

    #[test]
    fn a_file_a_test_could_read_counts_and_what_cargo_git_or_kalku_keep_does_not() {
        let root = workspace("data");
        let before = prints(&root);
        write(&root, "tool/target/debug/x", "built");
        write(&root, "tool/.git/HEAD", "ref");
        write(&root, "tool/.kalku/runs.ndjson", "a run");
        assert_eq!(before, prints(&root));
        write(&root, "tool/tests/data/input.txt", "data");
        assert_ne!(before["tool-id"], prints(&root)["tool-id"]);
    }

    #[test]
    fn a_package_inside_another_counts_only_for_itself() {
        let root = workspace("nested");
        let mut meta = metadata(&root);
        meta["packages"][1]["manifest_path"] = json!(root.join("app/core/Cargo.toml"));
        meta["resolve"]["nodes"][0]["deps"] = json!([]);
        write(&root, "app/core/src/lib.rs", "core");
        let before = fingerprints(&meta, &root, "base");
        write(&root, "app/core/src/lib.rs", "core, changed");
        let after = fingerprints(&meta, &root, "base");
        assert_eq!(before["app-id"], after["app-id"]);
        assert_ne!(before["core-id"], after["core-id"]);
    }

    // The package at the root of a project holds kalku's own directory, which
    // every run writes to.
    #[test]
    fn a_package_at_the_root_is_not_changed_by_a_run_of_kalku() {
        let root = scratch("root");
        write(&root, "Cargo.toml", "[package]");
        write(&root, "src/lib.rs", "lib");
        let meta = json!({
            "packages": [{"id": "p-id", "source": null,
                "manifest_path": root.join("Cargo.toml")}],
            "workspace_members": ["p-id"],
            "resolve": {"nodes": [{"id": "p-id", "deps": []}]}
        });
        let before = fingerprints(&meta, &root, "base");
        write(&root, ".kalku/runs.ndjson", "another run");
        assert_eq!(before, fingerprints(&meta, &root, "base"));
        write(&root, "src/lib.rs", "lib, changed");
        assert_ne!(before, fingerprints(&meta, &root, "base"));
    }

    #[test]
    fn two_copies_of_a_project_agree() {
        let one = workspace("copy-one");
        let two = workspace("copy-two");
        assert_eq!(prints(&one), prints(&two));
    }

    #[test]
    fn the_base_follows_the_toolchain_the_lock_and_what_the_tests_are_given() {
        let root = workspace("base");
        let env = |extra: &[(&str, &str)]| -> Vec<(String, String)> {
            extra
                .iter()
                .map(|(k, v)| (k.to_string(), v.to_string()))
                .collect()
        };
        let first = base("0.1.0", "rustc 1.90.0", &env(&[("PATH", "/a")]), &root);
        assert_eq!(
            first,
            base("0.1.0", "rustc 1.90.0", &env(&[("PATH", "/b")]), &root)
        );
        assert_ne!(
            first,
            base("0.1.1", "rustc 1.90.0", &env(&[("PATH", "/a")]), &root)
        );
        assert_ne!(
            first,
            base("0.1.0", "rustc 1.91.0", &env(&[("PATH", "/a")]), &root)
        );
        assert_ne!(
            first,
            base(
                "0.1.0",
                "rustc 1.90.0",
                &env(&[("PATH", "/a"), ("RUSTFLAGS", "-O")]),
                &root
            )
        );
        write(&root, "Cargo.lock", "another lock");
        assert_ne!(
            first,
            base("0.1.0", "rustc 1.90.0", &env(&[("PATH", "/a")]), &root)
        );
    }
}
