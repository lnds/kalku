//! Newline-delimited framing with a ceiling.
//!
//! A line longer than the limit is discarded up to the next newline without
//! being buffered, and the next line is read as if nothing had happened: a
//! peer that sends gigabytes without a newline must not be able to make this
//! process hold them, and one oversized message must not cost the ones after
//! it.

use std::io::{self, BufRead};

/// One line of input, or the news that it was too long to take.
#[derive(Debug, PartialEq, Eq)]
pub enum Line {
    Text(String),
    TooLong,
}

// A line longer than the limit is discarded up to the next newline without
// being buffered: a peer that sends gigabytes without one must not be able
// to make this process hold them.
pub fn read_line(input: &mut impl BufRead, max: usize) -> io::Result<Option<Line>> {
    let mut bytes: Vec<u8> = Vec::new();
    let mut too_long = false;
    let mut any = false;
    loop {
        let (consumed, done) = {
            let available = input.fill_buf()?;
            if available.is_empty() {
                return Ok(if any {
                    Some(finish(bytes, too_long))
                } else {
                    None
                });
            }
            any = true;
            match available.iter().position(|&b| b == b'\n') {
                Some(at) => {
                    if !too_long && bytes.len() + at <= max {
                        bytes.extend_from_slice(&available[..at]);
                    } else {
                        too_long = true;
                    }
                    (at + 1, true)
                }
                None => {
                    if !too_long && bytes.len() + available.len() <= max {
                        bytes.extend_from_slice(available);
                    } else {
                        too_long = true;
                        bytes.clear();
                    }
                    (available.len(), false)
                }
            }
        };
        input.consume(consumed);
        if done {
            return Ok(Some(finish(bytes, too_long)));
        }
    }
}

fn finish(bytes: Vec<u8>, too_long: bool) -> Line {
    if too_long {
        return Line::TooLong;
    }
    match String::from_utf8(bytes) {
        Ok(text) => Line::Text(text.trim_end_matches('\r').to_string()),
        // The protocol is UTF-8; bytes that are not are not JSON.
        Err(_) => Line::Text(String::from("\u{0}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lines(input: &str, max: usize) -> Vec<Line> {
        let mut reader = io::Cursor::new(input.as_bytes().to_vec());
        let mut out = Vec::new();
        while let Some(l) = read_line(&mut reader, max).unwrap() {
            out.push(l);
        }
        out
    }

    #[test]
    fn a_line_at_the_limit_is_read_and_one_past_it_is_not() {
        assert_eq!(lines("abcd\n", 4), [Line::Text("abcd".into())]);
        assert_eq!(lines("abcde\n", 4), [Line::TooLong]);
    }

    // One oversized message must not cost the messages after it.
    #[test]
    fn the_line_after_a_long_one_is_still_read() {
        assert_eq!(
            lines("toolong\nok\n", 4),
            [Line::TooLong, Line::Text("ok".into())]
        );
    }

    #[test]
    fn a_last_line_with_no_newline_is_still_a_line() {
        assert_eq!(
            lines("ab\ncd", 4),
            [Line::Text("ab".into()), Line::Text("cd".into())]
        );
    }

    #[test]
    fn a_carriage_return_is_not_part_of_the_message() {
        assert_eq!(lines("ab\r\n", 8), [Line::Text("ab".into())]);
    }

    #[test]
    fn nothing_is_nothing() {
        assert_eq!(lines("", 4), []);
    }

    // The line arrives in pieces smaller than itself, as it does from a pipe.
    #[test]
    fn a_line_longer_than_the_reader_buffer_is_still_one_line() {
        let mut reader = io::BufReader::with_capacity(3, "abcdefgh\nij\n".as_bytes());

        assert_eq!(
            read_line(&mut reader, 100).unwrap(),
            Some(Line::Text("abcdefgh".into()))
        );
        assert_eq!(
            read_line(&mut reader, 100).unwrap(),
            Some(Line::Text("ij".into()))
        );
        assert_eq!(read_line(&mut reader, 100).unwrap(), None);
    }

    #[test]
    fn a_long_line_arriving_in_pieces_is_too_long_and_the_next_is_whole() {
        let mut reader = io::BufReader::with_capacity(4, "abcdefghij\nok\n".as_bytes());

        assert_eq!(read_line(&mut reader, 6).unwrap(), Some(Line::TooLong));
        assert_eq!(
            read_line(&mut reader, 6).unwrap(),
            Some(Line::Text("ok".into()))
        );
    }

    // The limit is on the line, so a line of exactly `max` bytes is whole and
    // one byte more is not, however the bytes arrive.
    #[test]
    fn a_line_of_exactly_the_limit_is_whole_and_one_byte_more_is_too_long() {
        for capacity in [2, 3, 64] {
            let read = |text: &str| {
                let mut reader = io::BufReader::with_capacity(capacity, text.as_bytes());
                read_line(&mut reader, 3).unwrap()
            };

            assert_eq!(read("abc\n"), Some(Line::Text("abc".into())), "{capacity}");
            assert_eq!(read("abcd\n"), Some(Line::TooLong), "{capacity}");
            assert_eq!(read("abc"), Some(Line::Text("abc".into())), "{capacity}");
            assert_eq!(read("abcd"), Some(Line::TooLong), "{capacity}");
        }
    }

    #[test]
    fn bytes_that_are_not_utf8_are_a_line_that_is_not_json() {
        let mut reader = io::BufReader::new(&b"\xff\xfe\nok\n"[..]);

        assert_eq!(
            read_line(&mut reader, 100).unwrap(),
            Some(Line::Text("\u{0}".into()))
        );
        assert_eq!(
            read_line(&mut reader, 100).unwrap(),
            Some(Line::Text("ok".into()))
        );
    }

    #[test]
    fn a_carriage_return_before_the_newline_is_not_part_of_the_line() {
        let mut reader = io::BufReader::new(&b"ab\r\ncd\r\n"[..]);

        assert_eq!(
            read_line(&mut reader, 100).unwrap(),
            Some(Line::Text("ab".into()))
        );
        assert_eq!(
            read_line(&mut reader, 100).unwrap(),
            Some(Line::Text("cd".into()))
        );
        assert_eq!(read_line(&mut reader, 100).unwrap(), None);
    }
}
