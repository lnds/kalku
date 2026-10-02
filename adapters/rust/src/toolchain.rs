//! Which Rust this kalku is running against.
//!
//! kalku measures the 2024 edition, which needs a compiler that has it:
//! Rust 1.85 or later. Asking at `hello` means a project on an older
//! toolchain is refused with that sentence, rather than failing later in a
//! build with an error about something else.

use std::process::Command;

/// The first release with the 2024 edition.
pub const MINIMUM: (u32, u32, u32) = (1, 85, 0);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Toolchain {
    /// The first line of `rustc --version`, for the human-readable runtime.
    pub version_line: String,
    pub release: (u32, u32, u32),
    /// The target triple tests are built for, which a runner is named by.
    pub host: String,
}

impl Toolchain {
    pub fn is_recent_enough(&self) -> bool {
        self.release >= MINIMUM
    }
}

/// Ask `rustc`. An `Err` is a sentence about why it could not be asked.
pub fn probe() -> Result<Toolchain, String> {
    let out = Command::new("rustc")
        .arg("-vV")
        .output()
        .map_err(|e| format!("cannot run `rustc`: {e}. Is a Rust toolchain installed?"))?;
    if !out.status.success() {
        return Err(format!(
            "`rustc -vV` failed: {}",
            String::from_utf8_lossy(&out.stderr).trim()
        ));
    }
    parse(&String::from_utf8_lossy(&out.stdout))
}

pub fn parse(verbose: &str) -> Result<Toolchain, String> {
    let field = |name: &str| {
        verbose
            .lines()
            .find_map(|l| l.strip_prefix(name))
            .map(|v| v.trim().to_string())
    };
    let version_line = verbose.lines().next().unwrap_or("").trim().to_string();
    let release = field("release:").ok_or("`rustc -vV` did not say its release")?;
    let host = field("host:").ok_or("`rustc -vV` did not say its host")?;
    Ok(Toolchain {
        version_line,
        release: numbers(&release).ok_or_else(|| format!("cannot read the release `{release}`"))?,
        host,
    })
}

// `1.98.1`, or `1.86.0-nightly`: the numbers before any suffix.
fn numbers(release: &str) -> Option<(u32, u32, u32)> {
    let core = release.split(['-', '+']).next()?;
    let mut parts = core.split('.').map(|p| p.parse::<u32>().ok());
    Some((
        parts.next()??,
        parts.next()??,
        parts.next().unwrap_or(Some(0))?,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    const RUSTC: &str = "rustc 1.98.1 (48a229cea 2026-09-01) (Homebrew)\n\
        binary: rustc\ncommit-hash: 48a229cea\nhost: aarch64-apple-darwin\nrelease: 1.98.1\n";

    #[test]
    fn it_reads_what_rustc_says_about_itself() {
        let t = parse(RUSTC).unwrap();

        assert_eq!(t.release, (1, 98, 1));
        assert_eq!(t.host, "aarch64-apple-darwin");
        assert!(t.version_line.starts_with("rustc 1.98.1"));
        assert!(t.is_recent_enough());
    }

    // 2024 is the edition this kalku measures, and it needs 1.85.
    #[test]
    fn a_compiler_before_the_2024_edition_is_too_old() {
        let old = RUSTC.replace("release: 1.98.1", "release: 1.84.1");

        assert!(!parse(&old).unwrap().is_recent_enough());
        assert!(
            parse(&RUSTC.replace("release: 1.98.1", "release: 1.85.0"))
                .unwrap()
                .is_recent_enough()
        );
    }

    #[test]
    fn a_nightly_is_read_by_its_numbers() {
        let nightly = RUSTC.replace("release: 1.98.1", "release: 1.99.0-nightly");

        assert_eq!(parse(&nightly).unwrap().release, (1, 99, 0));
    }

    #[test]
    fn output_that_is_not_rustc_is_an_error_not_a_guess() {
        assert!(parse("hello\n").is_err());
        assert!(parse("release: x.y\nhost: z\n").is_err());
    }

    // A release with no patch number is patch 0, which is what Rust itself
    // means by `1.85`.
    #[test]
    fn a_release_without_a_patch_number_is_patch_zero() {
        assert_eq!(numbers("1.85"), Some((1, 85, 0)));
        assert_eq!(numbers("1.85.2"), Some((1, 85, 2)));
    }

    #[test]
    fn what_is_missing_from_rustc_is_named() {
        assert_eq!(
            parse("host: z\n").unwrap_err(),
            "`rustc -vV` did not say its release"
        );
        assert_eq!(
            parse("release: 1.98.1\n").unwrap_err(),
            "`rustc -vV` did not say its host"
        );
    }
}
