package kalku

import "testing"

// A position at the very start of a line is on that line, in column 1, and
// so is one on the first line of a file; a file may begin with a newline.
func TestAPositionIsOnTheLineItStarts(t *testing.T) {
	cases := []struct {
		text   string
		offset int
		want   Position
	}{
		{"ab\ncd\n", 0, Position{Line: 1, Col: 1, Byte: 0}},
		{"ab\ncd\n", 1, Position{Line: 1, Col: 2, Byte: 1}},
		{"ab\ncd\n", 3, Position{Line: 2, Col: 1, Byte: 3}},
		{"ab\ncd\n", 4, Position{Line: 2, Col: 2, Byte: 4}},
		{"\nab\n", 0, Position{Line: 1, Col: 1, Byte: 0}},
		{"\nab\n", 1, Position{Line: 2, Col: 1, Byte: 1}},
	}
	for _, c := range cases {
		if got := newSource(c.text).position(c.offset); got != c.want {
			t.Errorf("%q at %d: got %+v, want %+v", c.text, c.offset, got, c.want)
		}
	}
}

// A file of one line has its sites on line 1.
func TestASiteOnTheFirstLineIsThere(t *testing.T) {
	sites, err := FindSites("f.go", "package p; var x = 1 > 0\n", []string{"compare"}, nil)
	if err != nil || len(sites) != 1 {
		t.Fatalf("%v %v", sites, err)
	}
	if got := sites[0].Start; got.Line != 1 || got.Col != 22 || got.Byte != 21 {
		t.Fatalf("got %+v", got)
	}
}
