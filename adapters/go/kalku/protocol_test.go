package kalku

import (
	"bufio"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

const fixtures = "../../../docs/protocol/fixtures"

// The fixtures belong to the repository, outside this module. Measured on a
// copy, they are not there: that is a skip, except on CI, where a missing
// fixture is a broken checkout.
func needFixtures(t *testing.T) {
	t.Helper()
	if _, err := os.Stat(fixtures); err != nil {
		if os.Getenv("CI") != "" {
			t.Fatalf("the protocol fixtures are missing: %s", fixtures)
		}
		t.Skip("the protocol fixtures are not next to this module")
	}
}

func lines(t *testing.T, name string) []string {
	t.Helper()
	needFixtures(t)
	data, err := os.ReadFile(filepath.Join(fixtures, name))
	if err != nil {
		t.Fatalf("no fixture %s", name)
	}
	return strings.Split(strings.TrimRight(string(data), "\n"), "\n")
}

// Every request a kalku can be sent decodes.
func TestEveryRequestFixtureDecodes(t *testing.T) {
	needFixtures(t)
	files, _ := filepath.Glob(filepath.Join(fixtures, "kalku/requests/*.ndjson"))
	seen := 0
	for _, f := range files {
		for _, l := range lines(t, "kalku/requests/"+filepath.Base(f)) {
			seen++
			if _, err := Decode(l); err != nil {
				t.Errorf("%s: %v\n  %s", filepath.Base(f), err, l)
			}
		}
	}
	if seen < 10 {
		t.Fatalf("only %d request fixtures were read", seen)
	}
}

// A malformed line is refused with the kind the fixture names.
func TestEveryInvalidRequestIsRefusedWithItsKind(t *testing.T) {
	for _, entry := range lines(t, "invalid/kalku_requests.ndjson") {
		var v struct{ Expect, Line string }
		if err := json.Unmarshal([]byte(entry), &v); err != nil {
			t.Fatal(err)
		}
		_, err := Decode(v.Line)
		var d *DecodeError
		if !errors.As(err, &d) {
			t.Errorf("accepted a line that should be `%s`: %s", v.Expect, v.Line)
			continue
		}
		if d.Kind != v.Expect {
			t.Errorf("%s: got %s, want %s", v.Line, d.Kind, v.Expect)
		}
	}
}

// One oversized message costs only itself, and the line after it is read.
func TestFramingMatchesItsFixtures(t *testing.T) {
	for _, entry := range lines(t, "invalid/framing.ndjson") {
		var v struct {
			Expect string
			Max    int
			Input  string
		}
		if err := json.Unmarshal([]byte(entry), &v); err != nil {
			t.Fatal(err)
		}
		r := bufio.NewReaderSize(strings.NewReader(v.Input), 16)
		var got []Line
		for {
			l, err := ReadLine(r, v.Max)
			if err == io.EOF {
				break
			}
			if err != nil {
				t.Fatal(err)
			}
			got = append(got, l)
		}
		tooLong := slices.ContainsFunc(got, func(l Line) bool { return l.TooLong })
		if tooLong != (v.Expect == "line_too_long") {
			t.Errorf("%s: too long = %v", entry, tooLong)
		}
		if last := got[len(got)-1]; last.TooLong || !strings.Contains(last.Text, `"id":8`) {
			t.Errorf("%s: the last line was not read: %+v", entry, last)
		}
	}
}

// A site read from the wire is written back byte for byte.
func TestEverySiteShapeRoundTrips(t *testing.T) {
	for _, l := range lines(t, "shapes/site.ndjson") {
		r, err := Decode(`{"type":"cast","id":1,"wekufe":"w","tests":[],"site":` + l + `}`)
		if err != nil {
			t.Fatalf("%v: %s", err, l)
		}
		if got := r.Cast.Site.JSON(); got != l {
			t.Errorf("\n got %s\nwant %s", got, l)
		}
	}
}

// What this kalku says is canonical: it matches the fixtures byte for byte.
func TestWhatItSaysIsTheFixturesByteForByte(t *testing.T) {
	end := Position{Line: 42, Col: 9, Byte: 1182}
	original := ">="
	site := Site{
		SiteID: "c1f37a90e2b4", File: "lib/my_app/parser.ex", Enclosing: "MyApp.Parser.next_token/2", Ordinal: 1,
		Start: Position{Line: 42, Col: 7, Byte: 1180}, End: &end, Spell: "compare", Original: &original,
		Replacement: ">", Reload: "module",
	}
	said := map[string][]string{
		"error": {
			Error(1, "protocol_mismatch", "kalku speaks protocol 2, kaikai side speaks 1", true),
			Error(5, "unknown_test", "no test test/my_app/parser_test.exs:99", false),
			Error(11, "bad_request", "unknown_type: frobnicate", false),
		},
		"bye": {Bye(7)},
		"baseline_done/coverage": {
			BaselineDone(3, 18300, []Timed{{Test: "test/my_app/parser_test.exs:18", File: "test/my_app/parser_test.exs", DurationMS: 12}},
				Coverage{Inline: []Entry{{File: "lib/my_app/parser.ex", Line: 42, Tests: []string{"test/my_app/parser_test.exs:18"}}}}, nil, nil, nil),
			BaselineDone(3, 18300, []Timed{{Test: "test/my_app/parser_test.exs:18", File: "test/my_app/parser_test.exs", DurationMS: 12}},
				Coverage{PackedPath: "/tmp/kalku/my_app/coverage.json"}, nil, nil, nil),
			BaselineDone(3, 18300, []Timed{{Test: "test/my_app/parser_test.exs:18", File: "test/my_app/parser_test.exs", DurationMS: 12}},
				Coverage{Withheld: "The suite reached 1 line(s) that no single test is credited with (parser.ex:42)."}, nil, nil, nil),
		},
		"sites_found": {SitesFound(4, nil, nil)},
		"aborted":     {Aborted(9, 5, true), Aborted(9, 5, false)},
		"reset_done":  {ResetDone(10, true, 140)},
		"reloaded":    {Reloaded(6, []string{"MyApp.Parser"}, 95)},
		"prepared":    {Prepared(2, 41200, 312)},
		"cast_done": {
			CastDone(9, "7c2f0b9e41d6", Outcome{Kind: "crashed", Message: "80 of 83 selected test(s) ran: the run exited with status 1"}, 912),
		},
		"baseline_done": {
			BaselineDone(3, 9100,
				[]Timed{{Test: "test/my_app/parser_test.exs:18", File: "test/my_app/parser_test.exs", DurationMS: 12}}, Coverage{},
				[]Failure{{Test: "test/my_app/parser_test.exs:18", Message: "Assertion with == failed\ncode: assert next_token(\"\", 0) == :eof"}},
				[]string{"the build is in /tmp/kalku/my_app/build, not in _build"}, nil),
			BaselineDone(3, 4100,
				[]Timed{{Test: "test/my_app/lexer_test.exs:5", File: "test/my_app/lexer_test.exs", DurationMS: 9, Fingerprint: "51be0c3a9d24"}},
				Coverage{Inline: []Entry{{File: "lib/my_app/lexer.ex", Line: 12, Tests: []string{"test/my_app/lexer_test.exs:5"}}}},
				nil, nil, []string{"test/my_app/parser_test.exs:18"}),
		},
	}
	for name, messages := range said {
		file, _, _ := strings.Cut(name, "/")
		fixture := lines(t, "kalku/replies/"+file+".ndjson")
		for _, m := range messages {
			if !slices.Contains(fixture, m) {
				t.Errorf("%s is not in the fixtures:\n  %s", name, m)
			}
		}
	}
	if got, want := site.JSON(), lines(t, "shapes/site.ndjson")[0]; got != want {
		t.Errorf("\n got %s\nwant %s", got, want)
	}
}

// `type`, then `id`, then the fields in the order the tables list them,
// and nothing between them that is not needed.
func TestAReadyReplyIsInCanonicalOrder(t *testing.T) {
	said := Ready(1, "0.15.0", "go1.26.3 darwin/arm64", []string{"cast", "abort"})
	want := `{"type":"ready","id":1,"protocol":1,"language":"go","adapter":"0.15.0","runtime":"go1.26.3 darwin/arm64",` +
		`"spells":["arm","compare","connect","negate","literal","call"],"capabilities":["cast","abort"]}`
	if said != want {
		t.Errorf("\n got %s\nwant %s", said, want)
	}
}

// Strings are raw UTF-8, with only what JSON requires escaped.
func TestAStringIsEscapedOnlyWhereJSONRequires(t *testing.T) {
	got := Error(1, "c", "¡ñandú! <a&b> \"q\" \\ \n\t\x01", false)
	want := `{"type":"error","id":1,"code":"c","message":"¡ñandú! <a&b> \"q\" \\ \n\t\u0001","fatal":false}`
	if got != want {
		t.Errorf("\n got %s\nwant %s", got, want)
	}
}

// A baseline names the tests it may leave out with the fingerprint each had,
// and a malformed entry is refused rather than read as no offer.
func TestABaselineReadsWhatItMayReuse(t *testing.T) {
	r, err := Decode(`{"type":"baseline","id":3,"reuse":[{"test":"a_test.go:TestA","fingerprint":"f1"}]}`)
	if err != nil {
		t.Fatal(err)
	}
	if r.Reuse["a_test.go:TestA"] != "f1" || len(r.Reuse) != 1 {
		t.Fatalf("reuse = %v", r.Reuse)
	}
	if r, err := Decode(`{"type":"baseline","id":3}`); err != nil || r.Reuse != nil {
		t.Fatalf("a baseline with nothing to reuse: %v, %v", r, err)
	}
	if _, err := Decode(`{"type":"baseline","id":3,"reuse":[{"test":"a_test.go:TestA"}]}`); err == nil {
		t.Fatal("an offer without a fingerprint was taken")
	}
}
