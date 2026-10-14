package kalku

import (
	"sort"
	"unicode/utf8"
)

// source is the text of one file, read for positions.
type source struct {
	text  string
	lines []int // the byte offset each line starts at
}

func newSource(text string) *source {
	lines := []int{0}
	for i := 0; i < len(text); i++ {
		if text[i] == '\n' {
			lines = append(lines, i+1)
		}
	}
	return &source{text: text, lines: lines}
}

// position of a byte offset, with the column counted in characters.
func (s *source) position(offset int) Position {
	line := sort.Search(len(s.lines), func(i int) bool { return s.lines[i] > offset }) - 1
	start := s.lines[line]
	return Position{Line: line + 1, Col: utf8.RuneCountInString(s.text[start:offset]) + 1, Byte: offset}
}

// splice puts replacement where [from, to) was.
func splice(text string, from, to int, replacement string) string {
	return text[:from] + replacement + text[to:]
}
