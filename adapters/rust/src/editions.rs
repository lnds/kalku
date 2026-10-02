//! Which edition each package of a project is written in.
//!
//! kalku measures the 2024 edition only. A package on an older one is not
//! measured, and says why, rather than being read with rules that may not
//! apply to it: `gen` is a keyword in 2024, the scope of a temporary in an
//! `if let` changed, `unsafe extern` exists. Cargo is the authority on an
//! edition — it resolves `edition.workspace = true` — so it is asked rather
//! than the manifest being read by hand.

use serde_json::Value;
use std::path::{Path, PathBuf};
use std::process::Command;

/// The one edition this kalku measures.
pub const SUPPORTED: &str = "2024";

pub trait Editions {
    /// The edition of the package a file belongs to, `None` when it belongs
    /// to no package, and `Err` when the project cannot be read at all.
    fn of(&mut self, file: &Path) -> Result<Option<String>, String>;
}

/// The sentence for a package kalku does not measure.
pub fn unsupported(edition: &str) -> String {
    format!(
        "edition {edition}: kalku measures the {SUPPORTED} edition only. \
         Set `edition = \"{SUPPORTED}\"` in the package's Cargo.toml"
    )
}

/// `cargo metadata`, asked once and remembered.
pub struct Cargo {
    root: PathBuf,
    packages: Option<Vec<(PathBuf, String)>>,
}

impl Cargo {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Cargo {
            root: root.into(),
            packages: None,
        }
    }

    fn packages(&mut self) -> Result<&[(PathBuf, String)], String> {
        if self.packages.is_none() {
            self.packages = Some(read_packages(&self.root)?);
        }
        Ok(self.packages.as_deref().unwrap_or(&[]))
    }
}

impl Editions for Cargo {
    fn of(&mut self, file: &Path) -> Result<Option<String>, String> {
        // Cargo names a package by its real path, and a root is often
        // reached through a link: `/tmp` and `/var` are links on macOS. A
        // file compared by the path it was named with belongs to nothing.
        let named = self.root.join(file);
        let absolute = std::fs::canonicalize(&named).unwrap_or(named);
        let packages = self.packages()?;
        Ok(owner(packages, &absolute).map(str::to_string))
    }
}

/// The package that contains a path: the one whose directory is the longest
/// prefix of it, because a workspace member lives inside the workspace root.
pub fn owner<'a>(packages: &'a [(PathBuf, String)], file: &Path) -> Option<&'a str> {
    packages
        .iter()
        .filter(|(dir, _)| file.starts_with(dir))
        .max_by_key(|(dir, _)| dir.components().count())
        .map(|(_, edition)| edition.as_str())
}

fn read_packages(root: &Path) -> Result<Vec<(PathBuf, String)>, String> {
    let out = Command::new("cargo")
        .args(["metadata", "--no-deps", "--format-version", "1"])
        .current_dir(root)
        .output()
        .map_err(|e| format!("cannot run `cargo`: {e}"))?;
    if !out.status.success() {
        return Err(format!(
            "`cargo metadata` failed in {}: {}",
            root.display(),
            String::from_utf8_lossy(&out.stderr).trim()
        ));
    }
    parse_packages(&String::from_utf8_lossy(&out.stdout))
}

pub fn parse_packages(metadata: &str) -> Result<Vec<(PathBuf, String)>, String> {
    let value: Value = serde_json::from_str(metadata)
        .map_err(|e| format!("`cargo metadata` did not print JSON: {e}"))?;
    let packages = value["packages"]
        .as_array()
        .ok_or("`cargo metadata` listed no packages")?;
    packages
        .iter()
        .map(|p| {
            let manifest = p["manifest_path"]
                .as_str()
                .ok_or("a package has no manifest_path")?;
            let edition = p["edition"].as_str().ok_or("a package has no edition")?;
            let dir = Path::new(manifest)
                .parent()
                .ok_or("a manifest has no directory")?;
            let real = std::fs::canonicalize(dir).unwrap_or_else(|_| dir.to_path_buf());
            Ok((real, edition.to_string()))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn packages() -> Vec<(PathBuf, String)> {
        vec![
            (PathBuf::from("/w"), "2021".into()),
            (PathBuf::from("/w/crates/core"), "2024".into()),
        ]
    }

    // A workspace member lives inside the workspace root, so the root's own
    // package must not claim the member's files.
    #[test]
    fn a_file_belongs_to_the_innermost_package() {
        let p = packages();

        assert_eq!(
            owner(&p, Path::new("/w/crates/core/src/lib.rs")),
            Some("2024")
        );
        assert_eq!(owner(&p, Path::new("/w/src/main.rs")), Some("2021"));
    }

    #[test]
    fn a_file_outside_every_package_belongs_to_none() {
        assert_eq!(owner(&packages(), Path::new("/elsewhere/a.rs")), None);
    }

    #[test]
    fn a_package_is_not_its_neighbour_with_a_longer_name() {
        let p = vec![(PathBuf::from("/w/app"), "2024".to_string())];

        assert_eq!(owner(&p, Path::new("/w/app-extra/src/lib.rs")), None);
    }

    #[test]
    fn the_metadata_cargo_prints_is_read_for_manifests_and_editions() {
        let json = r#"{"packages":[
            {"name":"a","manifest_path":"/w/Cargo.toml","edition":"2021"},
            {"name":"b","manifest_path":"/w/crates/b/Cargo.toml","edition":"2024"}],
            "workspace_root":"/w"}"#;

        let read = parse_packages(json).unwrap();

        assert_eq!(read[0], (PathBuf::from("/w"), "2021".to_string()));
        assert_eq!(read[1], (PathBuf::from("/w/crates/b"), "2024".to_string()));
    }

    #[test]
    fn metadata_that_is_not_what_cargo_prints_is_an_error() {
        assert!(parse_packages("nonsense").is_err());
        assert!(parse_packages(r#"{"packages":[{"name":"a"}]}"#).is_err());
    }

    #[test]
    fn the_refusal_says_what_to_change() {
        let said = unsupported("2021");

        assert!(said.contains("edition 2021"));
        assert!(said.contains("edition = \"2024\""));
    }
}
