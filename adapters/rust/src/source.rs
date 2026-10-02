//! Positions in a source file, in the three ways they are counted.
//!
//! `syn` reports a line (1-based) and a column (0-based, in characters). The
//! protocol wants a line (1-based), a column (1-based, in characters) and a
//! byte offset (0-based) — and a wekufe is spliced by bytes. This is the one
//! place those meet, so a site cannot disagree with itself about where it is.

/// A source file's text, with where each line begins.
pub struct Source<'a> {
    pub text: &'a str,
    line_starts: Vec<usize>,
}

/// A position as the protocol states it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Position {
    pub line: usize,
    pub col: usize,
    pub byte: usize,
}

impl<'a> Source<'a> {
    pub fn new(text: &'a str) -> Self {
        let mut line_starts = vec![0];
        line_starts.extend(text.match_indices('\n').map(|(at, _)| at + 1));
        Source { text, line_starts }
    }

    /// The byte offset of what `syn` calls `(line, column)`, or `None` when
    /// that position is not inside the text.
    pub fn byte_at(&self, line: usize, column: usize) -> Option<usize> {
        let start = *self.line_starts.get(line.checked_sub(1)?)?;
        let rest = &self.text[start..];
        let on_line = rest.split('\n').next().unwrap_or("");
        if column == on_line.chars().count() {
            return Some(start + on_line.len());
        }
        on_line.char_indices().nth(column).map(|(at, _)| start + at)
    }

    /// The protocol's position for a byte offset.
    pub fn position(&self, byte: usize) -> Position {
        let line_index = match self.line_starts.binary_search(&byte) {
            Ok(exact) => exact,
            Err(after) => after - 1,
        };
        let start = self.line_starts[line_index];
        Position {
            line: line_index + 1,
            col: self.text[start..byte].chars().count() + 1,
            byte,
        }
    }

    /// The text between two byte offsets, when both fall on characters.
    pub fn slice(&self, from: usize, to: usize) -> Option<&'a str> {
        self.text.get(from..to)
    }

    /// The text with `from..to` replaced by `with`.
    pub fn splice(&self, from: usize, to: usize, with: &str) -> String {
        let mut out = String::with_capacity(self.text.len() + with.len());
        out.push_str(&self.text[..from]);
        out.push_str(with);
        out.push_str(&self.text[to..]);
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_column_is_counted_in_characters_and_a_byte_in_bytes() {
        // `é` is two bytes and one character: the two counts part company
        // here, which is the whole reason this module exists.
        let src = Source::new("let é = 1;\nlet b = 2;\n");

        assert_eq!(src.byte_at(1, 4), Some(4));
        assert_eq!(src.byte_at(1, 5), Some(6));
        assert_eq!(
            src.position(6),
            Position {
                line: 1,
                col: 6,
                byte: 6
            }
        );
    }

    #[test]
    fn the_second_line_counts_from_its_own_start() {
        let src = Source::new("ab\ncd\n");

        assert_eq!(src.byte_at(2, 1), Some(4));
        assert_eq!(
            src.position(4),
            Position {
                line: 2,
                col: 2,
                byte: 4
            }
        );
    }

    #[test]
    fn the_end_of_a_line_is_a_position() {
        let src = Source::new("ab\ncd");

        assert_eq!(src.byte_at(1, 2), Some(2));
        assert_eq!(src.byte_at(2, 2), Some(5));
    }

    #[test]
    fn a_position_outside_the_text_is_none_not_a_guess() {
        let src = Source::new("ab\n");

        assert_eq!(src.byte_at(9, 0), None);
        assert_eq!(src.byte_at(0, 0), None);
        assert_eq!(src.byte_at(1, 9), None);
    }

    #[test]
    fn splicing_replaces_exactly_the_span() {
        let src = Source::new("if a >= 10 {}");

        assert_eq!(src.splice(5, 7, ">"), "if a > 10 {}");
        assert_eq!(src.splice(5, 7, ""), "if a  10 {}");
    }
}
