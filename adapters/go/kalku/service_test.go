package kalku

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestTheOldestGoIsTheFirstThatChoosesItsToolchain(t *testing.T) {
	for v, want := range map[string]bool{
		"go1.21.0 linux/amd64":  true,
		"go1.26.3 darwin/arm64": true,
		"go1.21rc2 linux/amd64": true,
		"go1.20.14 linux/amd64": false,
		"go1.9 linux/amd64":     false,
		"devel go1.27-abc123":   true,
		"":                      false,
	} {
		if got := recentEnough(v); got != want {
			t.Errorf("recentEnough(%q) = %v", v, got)
		}
	}
}

func ranWith(verdicts map[string]string, started ...string) *Ran {
	r := newRan(io.Discard)
	for name, v := range verdicts {
		r.verdicts[key("p", name)] = v
	}
	for _, name := range started {
		r.started[key("p", name)] = true
	}
	return r
}

func suite(names ...string) []Test {
	var out []Test
	for _, n := range names {
		out = append(out, Test{ID: "t.go:" + n, Package: "p", Name: n})
	}
	return out
}

func TestAVerdictIsCountedFromTheTestsNeverFromThePackage(t *testing.T) {
	tests := suite("TestA", "TestB")
	cases := []struct {
		name string
		ran  *Ran
		want Outcome
	}{
		{"all passed", ranWith(map[string]string{"TestA": "pass", "TestB": "pass"}), Outcome{Kind: "survived"}},
		{"a skip judges nothing and fails nothing", ranWith(map[string]string{"TestA": "skip", "TestB": "pass"}), Outcome{Kind: "survived"}},
		{"the second failed", ranWith(map[string]string{"TestA": "pass", "TestB": "fail"}), Outcome{Kind: "killed", KilledBy: "t.go:TestB"}},
		{"one started and its binary died", ranWith(map[string]string{"TestA": "pass"}, "TestA", "TestB"), Outcome{Kind: "killed", KilledBy: "t.go:TestB"}},
		{"none started", ranWith(map[string]string{}), Outcome{Kind: "crashed", Message: "0 of 2 selected test(s) ran: the run ended"}},
	}
	for _, c := range cases {
		if got := judge(c.ran, tests); got != c.want {
			t.Errorf("%s: got %+v, want %+v", c.name, got, c.want)
		}
	}
}

// A test binary that did not build is a compile error, whatever ran.
func TestABinaryThatDidNotBuildIsACompileError(t *testing.T) {
	r := ranWith(map[string]string{"TestA": "fail"})
	r.broken["p"] = true
	r.build.WriteString("p/a.go:3:1: undefined: x\n")
	got := judge(r, suite("TestA"))
	if got.Kind != "compile_error" || got.Message != "p/a.go:3:1: undefined: x" {
		t.Fatalf("got %+v", got)
	}
}

// Before Go 1.24 the build said what failed on stderr, and the package said
// only that it did.
func TestABuildFailureIsReadFromEitherShapeOfGoTestJSON(t *testing.T) {
	old := newRan(io.Discard)
	old.read(strings.NewReader("FAIL\tp [build failed]\n" +
		`{"Action":"start","Package":"q"}` + "\n" + `{"Action":"pass","Package":"q"}` + "\n"))
	_, _ = old.stderr.Write([]byte("# p\n./a.go:3:1: undefined: x\n"))
	if !old.broken["p"] || old.broken["q"] {
		t.Errorf("old shape: broken %v", old.broken)
	}
	if said, broken := old.Broken(); !broken || !strings.Contains(said, "undefined: x") {
		t.Errorf("old shape: %q %v", said, broken)
	}
	recent := newRan(io.Discard)
	recent.add(Event{Action: "build-output", ImportPath: "p [p.test]", Output: "./a.go:3:1: undefined: x\n"})
	recent.add(Event{Action: "build-fail", ImportPath: "p [p.test]"})
	recent.add(Event{Action: "fail", Package: "p", FailedBuild: "p [p.test]"})
	if said, broken := recent.Broken(); !broken || said != "./a.go:3:1: undefined: x" {
		t.Errorf("recent shape: %q %v", said, broken)
	}
}

// A subtest is judged as part of the test that ran it.
func TestASubtestBelongsToItsTest(t *testing.T) {
	r := newRan(io.Discard)
	for _, e := range []Event{
		{Action: "run", Package: "p", Test: "TestA"},
		{Action: "run", Package: "p", Test: "TestA/case"},
		{Action: "output", Package: "p", Test: "TestA/case", Output: "    a_test.go:9: wrong\n"},
		{Action: "fail", Package: "p", Test: "TestA/case"},
		{Action: "fail", Package: "p", Test: "TestA", Elapsed: 0.25},
	} {
		r.add(e)
	}
	a := suite("TestA")[0]
	if r.verdict(a) != "fail" || !strings.Contains(r.message(a), "wrong") || r.elapsed[key("p", "TestA")] != 0.25 {
		t.Fatalf("verdict %q, message %q", r.verdict(a), r.message(a))
	}
	if _, ok := r.verdicts[key("p", "TestA/case")]; ok {
		t.Fatal("a subtest was judged on its own")
	}
}

func TestListingKeepsTestsExamplesAndFuzzTargetsButNotBenchmarks(t *testing.T) {
	r := newRan(io.Discard)
	for _, name := range []string{"TestA", "BenchmarkB", "ExampleC", "FuzzD", "ok  \tp\t0.1s"} {
		r.add(Event{Action: "output", Package: "p", Output: name + "\n"})
	}
	got := r.listed()
	if len(got) != 3 || got[0][1] != "TestA" || got[1][1] != "ExampleC" || got[2][1] != "FuzzD" {
		t.Fatalf("got %v", got)
	}
}

func TestTheOverlayIsNamedAsTheFileItStandsFor(t *testing.T) {
	p := &Project{overlay: "/r/overlay/3"}
	got := p.Named("# p\n../../overlay/3/wekufe.go:11:19: cannot use 256", "internal/limits/limits.go")
	if got != "# p\ninternal/limits/limits.go:11:19: cannot use 256" {
		t.Fatalf("got %q", got)
	}
}

func greet(t *testing.T, s *Service, root string, options map[string]any) map[string]any {
	t.Helper()
	h := &Hello{Protocol: 1, Root: root, Reni: t.TempDir(), Env: map[string]string{}, Options: options}
	return reply(t, s.Handle(context.Background(), &Request{Type: "hello", ID: 1, Hello: h}))
}

func reply(t *testing.T, r Reply) map[string]any {
	t.Helper()
	var v map[string]any
	if err := json.Unmarshal([]byte(r.Line), &v); err != nil {
		t.Fatalf("not JSON: %q", r.Line)
	}
	return v
}

func module(t *testing.T, files map[string]string) string {
	t.Helper()
	root := t.TempDir()
	files["go.mod"] = "module example.com/m\n\ngo 1.21\n"
	for name, text := range files {
		path := filepath.Join(root, name)
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(text), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return root
}

func fake(version string, err error) *Service {
	return &Service{Probe: func(string, []string) (string, error) { return version, err }}
}

func TestAGreetingIsRefusedWhenItCannotBeServed(t *testing.T) {
	root := module(t, map[string]string{})
	cases := []struct {
		name    string
		s       *Service
		root    string
		options map[string]any
		code    string
	}{
		{"a setting it does not have", fake("go1.26.3 x", nil), root, map[string]any{"oracle": "make"}, "bad_options"},
		{"a directory with no go.mod", fake("go1.26.3 x", nil), t.TempDir(), nil, "not_a_go_module"},
		{"no go", fake("", errors.New("no `go` to run")), root, nil, "toolchain_missing"},
		{"a go too old", fake("go1.20.14 linux/amd64", nil), root, nil, "unsupported_toolchain"},
	}
	for _, c := range cases {
		got := greet(t, c.s, c.root, c.options)
		if got["type"] != "error" || got["code"] != c.code || got["fatal"] != true {
			t.Errorf("%s: got %v", c.name, got)
		}
	}
	if got := greet(t, fake("go1.20.14 linux/amd64", nil), root, nil); !strings.Contains(got["message"].(string), "go1.20.14") {
		t.Errorf("a refused Go is not named: %v", got)
	}
}

func TestNothingIsServedBeforeItsTurn(t *testing.T) {
	s := fake("go1.26.3 x", nil)
	ask := func(typ string) map[string]any {
		return reply(t, s.Handle(context.Background(), &Request{Type: typ, ID: 2, Sites: &SitesRequest{}}))
	}
	if got := ask("prepare"); got["code"] != "not_ready" || got["fatal"] != false {
		t.Errorf("prepare before hello: %v", got)
	}
	greet(t, s, module(t, map[string]string{}), nil)
	if got := ask("baseline"); got["code"] != "not_ready" {
		t.Errorf("baseline before prepare: %v", got)
	}
	if got := ask("delegate"); got["code"] != "bad_request" {
		t.Errorf("delegate: %v", got)
	}
}

func TestAFileThatYieldsNoSitesSaysWhy(t *testing.T) {
	root := module(t, map[string]string{
		"a.go":            "package m\n\nvar A = 1 > 0\n",
		"a_test.go":       "package m\n",
		"gen.go":          "// Code generated by x. DO NOT EDIT.\n\npackage m\n",
		"broken.go":       "package m\n\nfunc (\n",
		"never_plan9.go":  "//go:build plan9\n\npackage m\n\nvar B = 1 > 0\n",
		"nested/go.mod":   "module example.com/nested\n",
		"nested/n.go":     "package nested\n",
		"README.md":       "# m\n",
		"missing/gone.go": "",
	})
	if err := os.Remove(filepath.Join(root, "missing/gone.go")); err != nil {
		t.Fatal(err)
	}
	s := fake("go1.26.3 x", nil)
	greet(t, s, root, nil)
	files := []string{"a.go", "a_test.go", "gen.go", "broken.go", "never_plan9.go", "nested/n.go", "README.md", "missing/gone.go"}
	got := reply(t, s.Handle(context.Background(), &Request{Type: "sites", ID: 4, Sites: &SitesRequest{Files: files, Spells: Spells}}))
	if n := len(got["sites"].([]any)); n != 3 {
		t.Errorf("a.go has 3 sites, got %d", n)
	}
	reasons := map[string]string{}
	for _, k := range got["skipped"].([]any) {
		k := k.(map[string]any)
		reasons[k["file"].(string)] = k["reason"].(string)
	}
	want := map[string]string{
		"a_test.go": "test_file", "gen.go": "generated", "broken.go": "parse_error", "never_plan9.go": "not_built",
		"nested/n.go": "other_module", "README.md": "not_go", "missing/gone.go": "unreadable",
	}
	for file, reason := range want {
		if reasons[file] != reason {
			t.Errorf("%s: got %q, want %q", file, reasons[file], reason)
		}
	}
}

// A binary that died before its tests, at init, says why in the crash.
func TestACrashSaysWhatTheBinaryDiedOf(t *testing.T) {
	r := newRan(io.Discard)
	for _, out := range []string{"panic: invalid UUID length: 36\n", "\n", "goroutine 1 [running]:\n", "FAIL\tp\t0.2s\n"} {
		r.add(Event{Action: "output", Package: "p", Output: out})
	}
	got := judge(r, suite("TestA"))
	if got.Kind != "crashed" || got.Message != "0 of 1 selected test(s) ran: the run ended; p: panic: invalid UUID length: 36" {
		t.Fatalf("got %+v", got)
	}
}
