//! The sites of every fixture, against a golden file beside it.
//!
//! A golden is only as good as its review: regenerate with
//! `UPDATE_GOLDEN=1 cargo test --test golden`, then read the diff.

use kalku_rust::{protocol, sites, spell::CAST};
use std::fs;
use std::path::{Path, PathBuf};

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).to_path_buf()
}

fn fixtures() -> Vec<PathBuf> {
    let mut found = Vec::new();
    for spell in fs::read_dir(root().join("tests/fixtures")).unwrap() {
        for case in fs::read_dir(spell.unwrap().path()).unwrap() {
            let path = case.unwrap().path();
            if path.extension().is_some_and(|e| e == "rs") {
                found.push(path);
            }
        }
    }
    found.sort();
    found
}

fn relative(path: &Path) -> String {
    path.strip_prefix(root().join("tests/fixtures"))
        .unwrap()
        .to_string_lossy()
        .into_owned()
}

fn render(file: &str, text: &str) -> String {
    sites::find(file, text, &CAST, &[])
        .unwrap()
        .sites
        .iter()
        .map(|s| format!("{}\n", protocol::site_json(s)))
        .collect()
}

#[test]
fn every_fixture_matches_its_golden() {
    let fixtures = fixtures();
    assert!(
        fixtures.len() >= 7,
        "the fixtures went missing: {fixtures:?}"
    );

    for path in fixtures {
        let actual = render(&relative(&path), &fs::read_to_string(&path).unwrap());
        let golden = path.with_extension("sites.ndjson");
        if std::env::var_os("UPDATE_GOLDEN").is_some() {
            fs::write(&golden, &actual).unwrap();
        }
        let expected = fs::read_to_string(&golden)
            .unwrap_or_else(|_| panic!("no golden for {}", path.display()));
        assert_eq!(actual, expected, "{}", relative(&path));
    }
}

// Every site holds what it says it holds, and what it would become still
// parses. Checked on the fixtures and on this kalku's own source, which is
// real Rust written by someone with no intention of helping the finder.
#[test]
fn every_site_round_trips() {
    let mut files = fixtures();
    for entry in fs::read_dir(root().join("src")).unwrap() {
        let path = entry.unwrap().path();
        if path.extension().is_some_and(|e| e == "rs") {
            files.push(path);
        }
    }

    let mut total = 0;
    for path in files {
        let text = fs::read_to_string(&path).unwrap();
        let name = path.display().to_string();
        for site in sites::find(&name, &text, &CAST, &[]).unwrap().sites {
            total += 1;
            assert_eq!(
                &text[site.start.byte..site.end.byte],
                site.original,
                "{name}: {site:?}"
            );
            assert_ne!(site.original, site.replacement, "{name}: {site:?}");
            let wekufe = format!(
                "{}{}{}",
                &text[..site.start.byte],
                site.replacement,
                &text[site.end.byte..]
            );
            assert!(syn::parse_file(&wekufe).is_ok(), "{name}: {site:?}");
        }
    }
    assert!(
        total > 100,
        "only {total} sites: the walk is not finding what it should"
    );
}
