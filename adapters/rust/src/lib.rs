//! The Rust kalku.
//!
//! It speaks the kalku protocol on stdio like any other, and it does the
//! one thing a kalku is for: knows the language. Sites come from `syn`,
//! which is Rust's own parser, and never from a scan of the text. Planning,
//! scheduling, caching and scoring belong to the kaikai side.
//!
//! It measures the 2024 edition and nothing older, so there is one grammar
//! to be right about.

pub mod cancel;
pub mod coverage;
pub mod editions;
pub mod framing;
pub mod project;
pub mod protocol;
pub mod service;
pub mod sites;
pub mod source;
pub mod spell;
pub mod toolchain;

use framing::{Line, read_line};
use protocol::{DecodeError, ErrorKind, MAX_LINE, Request};
use service::{Service, Step};
use std::io::{self, BufRead, Write};
use std::sync::mpsc;
use std::thread;

/// What the thread that reads requests heard.
enum Heard {
    Line(Line),
    End,
    Broke(io::Error),
}

/// Answer requests from `input` on `output` until `shutdown` or end of input.
///
/// Requests are read on a thread of their own, so that an `abort` is heard
/// while a cast is still running; everything else is served in order, here.
pub fn serve(
    input: impl BufRead + Send + 'static,
    mut output: impl Write,
    service: &mut Service,
) -> io::Result<()> {
    let cancel = service.cancel();
    let (heard, requests) = mpsc::channel();
    thread::spawn(move || {
        let mut input = input;
        loop {
            let next = match read_line(&mut input, MAX_LINE) {
                Ok(Some(line)) => {
                    if let Line::Text(text) = &line {
                        if text.contains("\"abort\"") {
                            if let Ok((_, Request::Abort { cast })) = protocol::decode(text) {
                                cancel.ask(cast);
                            }
                        }
                    }
                    Heard::Line(line)
                }
                Ok(None) => Heard::End,
                Err(e) => Heard::Broke(e),
            };
            let last = !matches!(next, Heard::Line(_));
            if heard.send(next).is_err() || last {
                break;
            }
        }
    });
    for next in requests {
        let line = match next {
            Heard::Line(line) => line,
            Heard::End => break,
            Heard::Broke(e) => return Err(e),
        };
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
            Step::Quiet => continue,
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
    let stdout = io::stdout();
    exit_code(serve(
        io::BufReader::new(io::stdin()),
        stdout.lock(),
        &mut Service::new(),
    ))
}

// A conversation that ended is success; one that broke is said on stderr,
// where the channel is not, and fails.
fn exit_code(ended: io::Result<()>) -> i32 {
    match ended {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("kalku-rust: {e}");
            1
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_conversation_that_ended_exits_zero_and_one_that_broke_exits_one() {
        assert_eq!(exit_code(Ok(())), 0);
        assert_eq!(exit_code(Err(io::Error::other("broken pipe"))), 1);
    }
}
