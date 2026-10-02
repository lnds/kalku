//! The Rust kalku.
//!
//! It speaks the kalku protocol on stdio like any other, and it does the
//! one thing a kalku is for: knows the language. Sites come from `syn`,
//! which is Rust's own parser, and never from a scan of the text. Planning,
//! scheduling, caching and scoring belong to the kaikai side.
//!
//! It measures the 2024 edition and nothing older, so there is one grammar
//! to be right about.

pub mod editions;
pub mod framing;
pub mod protocol;
pub mod service;
pub mod sites;
pub mod source;
pub mod spell;
pub mod toolchain;

use framing::{Line, read_line};
use protocol::{DecodeError, ErrorKind, MAX_LINE};
use service::{Service, Step};
use std::io::{self, BufRead, Write};

/// Answer requests from `input` on `output` until `shutdown` or end of input.
pub fn serve(
    mut input: impl BufRead,
    mut output: impl Write,
    service: &mut Service,
) -> io::Result<()> {
    while let Some(line) = read_line(&mut input, MAX_LINE)? {
        let step = match line {
            Line::TooLong => Step::Reply(service.refuse(&DecodeError {
                kind: ErrorKind::LineTooLong,
                detail: format!("a line longer than {MAX_LINE} bytes"),
                id: None,
            })),
            Line::Text(text) => service.handle(&text),
        };
        let (reply, stop) = match step {
            Step::Reply(r) => (r, false),
            Step::Stop(r) => (r, true),
        };
        writeln!(output, "{reply}")?;
        output.flush()?;
        if stop {
            break;
        }
    }
    Ok(())
}

/// The kalku on stdio. The exit code is the process's.
pub fn run() -> i32 {
    let stdin = io::stdin();
    let stdout = io::stdout();
    match serve(stdin.lock(), stdout.lock(), &mut Service::new()) {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("kalku-rust: {e}");
            1
        }
    }
}
