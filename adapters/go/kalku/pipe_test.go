package kalku

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"testing"
	"time"
)

// The kalku as the kaikai side meets it: a real process, a real pipe, and
// a real module on disk. Every line it writes has to be a protocol message;
// one that is not would have it banished for writing nonsense.

var binary string

func TestMain(m *testing.M) {
	dir, err := os.MkdirTemp("", "kalku-go-bin")
	if err != nil {
		panic(err)
	}
	binary = filepath.Join(dir, "kalku-go")
	build := exec.Command("go", "build", "-o", binary, "../cmd/kalku-go")
	build.Stderr = os.Stderr
	if err := build.Run(); err != nil {
		panic(err)
	}
	code := m.Run()
	os.RemoveAll(dir)
	os.Exit(code)
}

type kalku struct {
	t      *testing.T
	cmd    *exec.Cmd
	stdin  io.WriteCloser
	stdout *bufio.Reader
}

func summon(t *testing.T) *kalku {
	t.Helper()
	cmd := exec.Command(binary)
	stdin, _ := cmd.StdinPipe()
	stdout, _ := cmd.StdoutPipe()
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	k := &kalku{t: t, cmd: cmd, stdin: stdin, stdout: bufio.NewReader(stdout)}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _ = cmd.Wait() })
	return k
}

func (k *kalku) send(m map[string]any) {
	k.t.Helper()
	line, _ := json.Marshal(m)
	if _, err := k.stdin.Write(append(line, '\n')); err != nil {
		k.t.Fatal(err)
	}
}

func (k *kalku) read() map[string]any {
	k.t.Helper()
	line, err := k.stdout.ReadString('\n')
	if err != nil {
		k.t.Fatalf("no reply: %v", err)
	}
	var v map[string]any
	if err := json.Unmarshal([]byte(line), &v); err != nil {
		k.t.Fatalf("stdout carried something that is not JSON (%v): %q", err, line)
	}
	return v
}

func (k *kalku) ask(m map[string]any) map[string]any {
	k.t.Helper()
	k.send(m)
	return k.read()
}

// A copy of the fixture module, so that its reni and its copy are the
// test's own.
func calc(t *testing.T) string {
	t.Helper()
	root := filepath.Join(t.TempDir(), "calc")
	if err := syncTree("../testdata/projects/calc", root, ""); err != nil {
		t.Fatal(err)
	}
	return root
}

func (k *kalku) start(root string, env map[string]string) map[string]any {
	k.t.Helper()
	ready := k.ask(map[string]any{
		"type": "hello", "id": 1, "protocol": 1, "root": root, "reni": filepath.Join(filepath.Dir(root), "reni"),
		"worker": 0, "inline_limit_bytes": 65536, "env": env,
	})
	if ready["type"] != "ready" {
		k.t.Fatalf("hello: %v", ready)
	}
	if got := k.ask(map[string]any{"type": "prepare", "id": 2}); got["type"] != "prepared" {
		k.t.Fatalf("prepare: %v", got)
	}
	return ready
}

func (k *kalku) sites(files ...string) []map[string]any {
	k.t.Helper()
	got := k.ask(map[string]any{"type": "sites", "id": 4, "files": files, "spells": Spells, "exclude_calls": []string{}})
	var out []map[string]any
	for _, s := range got["sites"].([]any) {
		out = append(out, s.(map[string]any))
	}
	return out
}

func find(t *testing.T, sites []map[string]any, line float64, spell, original string) map[string]any {
	t.Helper()
	for _, s := range sites {
		start := s["span"].(map[string]any)["start"].(map[string]any)
		if start["line"] == line && s["spell"] == spell && s["original"] == original {
			return s
		}
	}
	t.Fatalf("no %s site %q on line %v", spell, original, line)
	return nil
}

func (k *kalku) cast(id int, site map[string]any, tests []string) map[string]any {
	k.t.Helper()
	return k.ask(map[string]any{"type": "cast", "id": id, "wekufe": site["site_id"], "site": site, "tests": tests})
}

func (k *kalku) leave() {
	k.t.Helper()
	if got := k.ask(map[string]any{"type": "shutdown", "id": 99}); got["type"] != "bye" {
		k.t.Fatalf("shutdown: %v", got)
	}
	if err := k.cmd.Wait(); err != nil {
		k.t.Fatalf("exit: %v", err)
	}
}

var everyTest = []string{
	"calc_test.go:TestClamp", "calc_test.go:TestSign", "example_test.go:ExampleSign",
	"internal/limits/limits_test.go:TestWithin", "internal/limits/limits_test.go:TestRoom",
	"internal/order/order_test.go:TestFirst", "internal/order/order_test.go:TestSecond",
	"internal/slow/slow_test.go:TestTwice",
}

// The tests a baseline credits with a line.
func credited(base map[string]any, file string, line int) []string {
	var out []string
	for _, e := range base["coverage"].([]any) {
		e := e.(map[string]any)
		if e["file"] == file && e["line"] == float64(line) {
			for _, t := range e["tests"].([]any) {
				out = append(out, t.(string))
			}
		}
	}
	return out
}

// A test is credited with every line of a function it entered, so the case
// a test only evaluated on its way past is credited to it too; a package's
// declarations go to every test whose binary holds the package; a function
// no test calls is credited to none.
func TestTheBaselineSaysWhichTestsReachWhichLines(t *testing.T) {
	t.Parallel()
	k := summon(t)
	ready := k.start(calc(t), map[string]string{})
	if !strings.Contains(fmt.Sprint(ready["capabilities"]), "per_test_coverage") {
		t.Fatalf("ready: %v", ready)
	}
	base := k.ask(map[string]any{"type": "baseline", "id": 3})
	want := map[string][]string{
		"calc.go:24": {"calc_test.go:TestSign", "example_test.go:ExampleSign"},
		"calc.go:13": {"calc_test.go:TestClamp"},
		"internal/limits/limits.go:6": {"calc_test.go:TestClamp", "calc_test.go:TestSign", "example_test.go:ExampleSign",
			"internal/limits/limits_test.go:TestRoom", "internal/limits/limits_test.go:TestWithin"},
		"internal/limits/limits.go:31": nil,
	}
	for at, tests := range want {
		file, line, _ := strings.Cut(at, ":")
		n, _ := strconv.Atoi(line)
		got := credited(base, file, n)
		sort.Strings(got)
		sort.Strings(tests)
		if strings.Join(got, " ") != strings.Join(tests, " ") {
			t.Errorf("%s: got %v, want %v", at, got, tests)
		}
	}
	k.leave()
}

// A test that passes in the suite and fails alone reaches something else
// alone than in the suite, so no coverage is reported, and why is said.
func TestCoverageIsWithheldWhenATestNeedsAnotherBeforeIt(t *testing.T) {
	t.Parallel()
	k := summon(t)
	k.start(calc(t), map[string]string{"CALC_ORDER": "1"})
	base := k.ask(map[string]any{"type": "baseline", "id": 3})
	if base["status"] != "green" || base["coverage"] != nil {
		t.Fatalf("baseline: %v", base)
	}
	if said, _ := base["coverage_withheld"].(string); !strings.Contains(said, "internal/order/order_test.go:TestSecond") {
		t.Fatalf("withheld: %q", said)
	}
	k.leave()
}

func TestItMeasuresAModuleEndToEnd(t *testing.T) {
	t.Parallel()
	root := calc(t)
	k := summon(t)
	ready := k.start(root, map[string]string{})
	if ready["language"] != "go" || !strings.HasPrefix(ready["runtime"].(string), "go1.") {
		t.Fatalf("ready: %v", ready)
	}

	base := k.ask(map[string]any{"type": "baseline", "id": 3})
	if base["status"] != "green" {
		t.Fatalf("baseline: %v", base)
	}
	var ids []string
	for _, x := range base["tests"].([]any) {
		ids = append(ids, x.(map[string]any)["test"].(string))
	}
	if strings.Join(ids, " ") != strings.Join(everyTest, " ") {
		t.Fatalf("tests: %v", ids)
	}

	sites := k.sites("calc.go", "internal/limits/limits.go")
	cases := []struct {
		file           string
		line           float64
		spell, orig    string
		outcome, by, m string
	}{
		{"calc.go", 13, "negate", "n > limits.High", "killed", "calc_test.go:TestClamp", ""},
		{"calc.go", 13, "compare", ">", "survived", "", ""},
		// A wekufe in a package another imports is judged by that one's tests.
		{"internal/limits/limits.go", 6, "literal", "10", "killed", "calc_test.go:TestClamp", ""},
		{"internal/limits/limits.go", 10, "literal", "255", "killed", "internal/limits/limits_test.go:TestRoom", ""},
		{"internal/limits/limits.go", 14, "literal", "4", "compile_error", "", "internal/limits/limits.go:"},
		{"internal/limits/limits.go", 21, "connect", "&&", "killed", "internal/limits/limits_test.go:TestWithin", ""},
	}
	for i, c := range cases {
		var site map[string]any
		for _, s := range sites {
			start := s["span"].(map[string]any)["start"].(map[string]any)
			if s["file"] == c.file && start["line"] == c.line && s["spell"] == c.spell && s["original"] == c.orig {
				site = s
			}
		}
		if site == nil {
			t.Fatalf("no site %v", c)
		}
		got := k.cast(10+i, site, everyTest)
		if got["outcome"] != c.outcome || (c.by != "" && got["killed_by"] != c.by) {
			t.Errorf("%s %q: got %v", c.spell, c.orig, got)
		}
		if said, _ := got["message"].(string); c.m != "" && !strings.Contains(said, c.m) {
			t.Errorf("%s %q: the message does not name the file: %q", c.spell, c.orig, said)
		}
	}

	// The copy is as `prepare` left it: a wekufe is never written into it.
	copied, _ := os.ReadFile(filepath.Join(filepath.Dir(root), "reni/work/0/calc.go"))
	original, _ := os.ReadFile(filepath.Join(root, "calc.go"))
	if string(copied) != string(original) {
		t.Error("the copy was changed by a cast")
	}
	k.leave()
}

// A kalku that ran the baseline judges as one that was only prepared, which
// is what `cast_after_baseline` claims.
func TestAfterTheBaselineItJudgesAsAKalkuOnlyPrepared(t *testing.T) {
	t.Parallel()
	verdicts := func(baseline bool) []string {
		root := calc(t)
		k := summon(t)
		k.start(root, map[string]string{})
		if baseline {
			k.ask(map[string]any{"type": "baseline", "id": 3})
		}
		var out []string
		for i, s := range k.sites("calc.go", "internal/limits/limits.go") {
			got := k.cast(10+i, s, everyTest)
			out = append(out, s["site_id"].(string)+" "+got["outcome"].(string))
		}
		k.leave()
		return out
	}
	after, only := verdicts(true), verdicts(false)
	if strings.Join(after, "\n") != strings.Join(only, "\n") {
		t.Fatalf("after a baseline:\n%s\nonly prepared:\n%s", strings.Join(after, "\n"), strings.Join(only, "\n"))
	}
}

// What the slow test runs as, found by the directory its binary was built in.
func running(t *testing.T, tmp string) bool {
	t.Helper()
	out, _ := exec.Command("pgrep", "-f", tmp).Output()
	return strings.TrimSpace(string(out)) != ""
}

func slowCast(t *testing.T) (*kalku, string) {
	t.Helper()
	root := calc(t)
	tmp := filepath.Join(filepath.Dir(root), "gotmp")
	if err := os.MkdirAll(tmp, 0o755); err != nil {
		t.Fatal(err)
	}
	k := summon(t)
	k.start(root, map[string]string{"CALC_SLOW": "1", "GOTMPDIR": tmp})
	site := find(t, k.sites("internal/slow/slow.go"), 7, "literal", "2")
	k.send(map[string]any{"type": "cast", "id": 5, "wekufe": site["site_id"], "site": site, "tests": []string{"internal/slow/slow_test.go:TestTwice"}})
	deadline := time.Now().Add(30 * time.Second)
	for !running(t, tmp) {
		if time.Now().After(deadline) {
			t.Fatal("the slow test never started")
		}
		time.Sleep(100 * time.Millisecond)
	}
	return k, tmp
}

// An abort stops the cast in place: no `cast_done`, the tests it started
// are ended, and the kalku goes on to the next request.
func TestAnAbortStopsTheCastAndTheKalkuGoesOn(t *testing.T) {
	t.Parallel()
	k, tmp := slowCast(t)
	got := k.ask(map[string]any{"type": "abort", "id": 6, "cast": 5})
	if got["type"] != "aborted" || got["cast"] != float64(5) || got["restored"] != true {
		t.Fatalf("abort: %v", got)
	}
	time.Sleep(200 * time.Millisecond)
	if running(t, tmp) {
		t.Error("the aborted cast's tests are still running")
	}
	if got := k.ask(map[string]any{"type": "sites", "id": 7, "files": []string{}, "spells": Spells, "exclude_calls": []string{}}); got["type"] != "sites_found" {
		t.Fatalf("after the abort: %v", got)
	}
	k.leave()
}

// Input that ends with no `shutdown` is a run that is gone: the kalku ends
// at once, and so does what it started.
func TestARunThatIsGoneTakesItsTestsWithIt(t *testing.T) {
	t.Parallel()
	k, tmp := slowCast(t)
	started := time.Now()
	k.stdin.Close()
	if err := k.cmd.Wait(); err != nil {
		t.Fatalf("exit: %v", err)
	}
	if time.Since(started) > 5*time.Second {
		t.Errorf("it took %v to leave", time.Since(started))
	}
	time.Sleep(200 * time.Millisecond)
	if running(t, tmp) {
		t.Error("the tests of a run that is gone are still running")
	}
}

// Requests after a `shutdown` that ended the input are still answered.
func TestEverythingBeforeAShutdownIsAnswered(t *testing.T) {
	k := summon(t)
	k.send(map[string]any{"type": "frobnicate", "id": 3})
	k.send(map[string]any{"type": "shutdown", "id": 4})
	k.stdin.Close()
	if got := k.read(); got["code"] != "bad_request" || got["id"] != float64(3) {
		t.Fatalf("got %v", got)
	}
	if got := k.read(); got["type"] != "bye" {
		t.Fatalf("got %v", got)
	}
	if err := k.cmd.Wait(); err != nil {
		t.Fatal(err)
	}
}

// The fingerprint of each test, as a baseline gave it.
func fingerprints(t *testing.T, base map[string]any) map[string]string {
	t.Helper()
	out := map[string]string{}
	for _, x := range base["tests"].([]any) {
		x := x.(map[string]any)
		f, _ := x["fingerprint"].(string)
		if f == "" {
			t.Fatalf("a test with no fingerprint: %v", x)
		}
		out[x["test"].(string)] = f
	}
	return out
}

func offer(prints map[string]string) []any {
	var out []any
	for test, f := range prints {
		out = append(out, map[string]any{"test": test, "fingerprint": f})
	}
	return out
}

func strs(v any) []string {
	var out []string
	for _, x := range v.([]any) {
		out = append(out, x.(string))
	}
	sort.Strings(out)
	return out
}

// A test whose package's fingerprint has not changed is not run again; one
// whose package, or any package its test binary is built from, has changed
// runs. The module's root holds every package below it, so its tests run
// whatever changed, though not when kalku writes down a run of its own.
func TestABaselineRunsOnlyTheTestsOfWhatChanged(t *testing.T) {
	t.Parallel()
	root := calc(t)
	first := summon(t)
	if ready := first.start(root, map[string]string{}); !strings.Contains(fmt.Sprint(ready["capabilities"]), "partial_baseline") {
		t.Fatalf("ready: %v", ready)
	}
	prints := fingerprints(t, first.ask(map[string]any{"type": "baseline", "id": 3}))
	first.leave()

	if err := os.MkdirAll(filepath.Join(root, ".kalku"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, ".kalku/runs.ndjson"), []byte("a run\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	again := summon(t)
	again.start(root, map[string]string{})
	same := again.ask(map[string]any{"type": "baseline", "id": 3, "reuse": offer(prints)})
	if len(same["tests"].([]any)) != 0 || strings.Join(strs(same["reused"]), " ") != strings.Join(sorted(everyTest), " ") {
		t.Fatalf("nothing changed, and the baseline said: %v", same)
	}
	if same["status"] != "green" {
		t.Fatalf("a baseline that ran nothing is green: %v", same)
	}
	again.leave()

	order := filepath.Join(root, "internal/order/order.go")
	text, _ := os.ReadFile(order)
	if err := os.WriteFile(order, append(text, []byte("\n// changed\n")...), 0o644); err != nil {
		t.Fatal(err)
	}
	changed := summon(t)
	changed.start(root, map[string]string{})
	base := changed.ask(map[string]any{"type": "baseline", "id": 3, "reuse": offer(prints)})
	reused := strings.Join(strs(base["reused"]), " ")
	want := "internal/limits/limits_test.go:TestRoom internal/limits/limits_test.go:TestWithin internal/slow/slow_test.go:TestTwice"
	if reused != want {
		t.Fatalf("reused %s, want %s", reused, want)
	}
	now := fingerprints(t, base)
	if now["internal/order/order_test.go:TestFirst"] == prints["internal/order/order_test.go:TestFirst"] {
		t.Error("the fingerprint of a changed package did not change")
	}
	// The coverage is of the tests that ran: a reused test brings its own.
	for _, e := range base["coverage"].([]any) {
		for _, test := range e.(map[string]any)["tests"].([]any) {
			if strings.Contains(reused, test.(string)) {
				t.Errorf("a reused test is credited with a line: %v", e)
			}
		}
	}
	changed.leave()
}

func sorted(xs []string) []string {
	out := append([]string{}, xs...)
	sort.Strings(out)
	return out
}
