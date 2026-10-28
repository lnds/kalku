package kalku

import (
	"context"
	"errors"
	"fmt"
	"go/build"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Oldest is the oldest Go this kalku measures: the first whose `go` command
// chooses the toolchain a module's go.mod asks for.
var Oldest = [2]int{1, 21}

// Service answers requests, one at a time, in the order they came.
type Service struct {
	hello   *Hello
	project *Project
	// Probe asks which Go a module is built by, in the module's directory.
	Probe func(root string, env []string) (string, error)
	// Prepared is set once `prepare` has listed the suite.
	prepared bool
}

// NewService is the service as it runs.
func NewService() *Service { return &Service{Probe: probe} }

// Reply is what to say to a request; Quiet when it was a cast that was
// aborted, and an aborted cast gets no answer of its own.
type Reply struct {
	Line  string
	Quiet bool
	Last  bool
}

func say(line string) Reply { return Reply{Line: line} }

// Refuse answers a line that could not be read.
func Refuse(err error) Reply {
	var d *DecodeError
	if errors.As(err, &d) {
		id := int64(0)
		if d.ID != nil {
			id = *d.ID
		}
		return say(Error(id, "bad_request", d.Kind+": "+d.Detail, false))
	}
	return say(Error(0, "bad_request", err.Error(), false))
}

// Handle answers one request. ctx is cancelled when the kaikai side asks
// for the request, a cast, to stop.
func (s *Service) Handle(ctx context.Context, r *Request) Reply {
	switch r.Type {
	case "hello":
		return s.greet(r.ID, r.Hello)
	case "shutdown":
		return Reply{Line: Bye(r.ID), Last: true}
	case "abort":
		// The cast was stopped as the abort was heard, and nothing it
		// did outlives it: a wekufe is never written into the copy.
		return say(Aborted(r.ID, r.Abort, true))
	case "delegate":
		return say(Error(r.ID, "bad_request", "this kalku casts its own wekufe and does not delegate", false))
	}
	if s.project == nil {
		return say(Error(r.ID, "not_ready", "`hello` has to come first", false))
	}
	switch r.Type {
	case "sites":
		return say(s.sites(r.ID, r.Sites))
	case "prepare":
		return say(s.prepare(ctx, r.ID))
	case "reload":
		return say(s.reload(r.ID, r.Reload))
	case "reset":
		// Every cast runs in processes of its own, so there is no state
		// held here for a wekufe to dirty.
		return say(ResetDone(r.ID, true, 0))
	}
	if !s.prepared {
		return say(Error(r.ID, "not_ready", "`prepare` has to come first", false))
	}
	switch r.Type {
	case "baseline":
		return say(s.baseline(ctx, r.ID, r.Reuse))
	case "cast":
		return s.cast(ctx, r.ID, r.Cast)
	}
	return say(Error(r.ID, "bad_request", "this kalku does not answer `"+r.Type+"`", false))
}

func (s *Service) greet(id int64, h *Hello) Reply {
	if h.Protocol != Protocol {
		return say(Error(id, "protocol_mismatch",
			fmt.Sprintf("kalku speaks protocol %d, kaikai side speaks %d", Protocol, h.Protocol), true))
	}
	if len(h.Options) > 0 {
		keys := make([]string, 0, len(h.Options))
		for k := range h.Options {
			keys = append(keys, "go."+k)
		}
		sort.Strings(keys)
		return say(Error(id, "bad_options",
			strings.Join(keys, ", ")+": the Go kalku has no settings of its own", true))
	}
	if _, err := os.Stat(filepath.Join(h.Root, "go.mod")); err != nil {
		return say(Error(id, "not_a_go_module", h.Root+" has no go.mod: the Go kalku measures one module, from its root", true))
	}
	project := NewProject(h)
	version, err := s.Probe(h.Root, project.env)
	if err != nil {
		return say(Error(id, "toolchain_missing", err.Error(), true))
	}
	if !recentEnough(version) {
		return say(Error(id, "unsupported_toolchain",
			fmt.Sprintf("%s is older than go%d.%d, the oldest Go this kalku measures", version, Oldest[0], Oldest[1]), true))
	}
	s.hello, s.project = h, project
	return say(Ready(id, Version, version, []string{"cast", "per_test_coverage", "abort", "cast_after_baseline", "partial_baseline"}))
}

// `go version` in the module's directory, which is the Go its go.mod
// chooses: `go1.26.3 darwin/arm64`.
func probe(root string, env []string) (string, error) {
	cmd := exec.Command("go", "version")
	cmd.Dir, cmd.Env = root, env
	out, err := cmd.Output()
	if err != nil {
		var exit *exec.ExitError
		if errors.As(err, &exit) {
			return "", fmt.Errorf("`go version` failed: %s", strings.TrimSpace(string(exit.Stderr)))
		}
		return "", fmt.Errorf("no `go` to run: %v", err)
	}
	return strings.TrimPrefix(strings.TrimSpace(string(out)), "go version "), nil
}

// True when `go1.22.5 linux/amd64` is Oldest or later. A development build
// (`devel ...`) is taken to be recent.
func recentEnough(version string) bool {
	v, _, _ := strings.Cut(version, " ")
	if !strings.HasPrefix(v, "go1.") {
		return strings.HasPrefix(v, "devel")
	}
	parts := strings.FieldsFunc(strings.TrimPrefix(v, "go"), func(r rune) bool { return r < '0' || r > '9' })
	if len(parts) < 2 {
		return false
	}
	major, _ := strconv.Atoi(parts[0])
	minor, _ := strconv.Atoi(parts[1])
	return major > Oldest[0] || (major == Oldest[0] && minor >= Oldest[1])
}

func (s *Service) prepare(ctx context.Context, id int64) string {
	started := time.Now()
	if err := s.project.Copy(); err != nil {
		return Error(id, "prepare_failed", "cannot copy the module into the reni: "+err.Error(), true)
	}
	packages, err := s.project.Build(ctx)
	if err != nil {
		return Error(id, "prepare_failed", err.Error(), true)
	}
	s.prepared = true
	return Prepared(id, since(started), packages)
}

// Every test runs, and runs for real: no result comes from the test cache.
// The exception is a test the kaikai side offers to reuse, which is left out
// when its package has the fingerprint it was offered with: nothing it runs
// has changed since it passed.
func (s *Service) baseline(ctx context.Context, id int64, reuse map[string]string) string {
	started := time.Now()
	prints, err := s.project.Fingerprints()
	if err != nil {
		// Without fingerprints every test runs, and none is offered back.
		fmt.Fprintln(os.Stderr, "kalku-go: the tests have no fingerprint:", err)
		prints = nil
	}
	var tests []Test
	var reused []string
	for _, t := range s.project.Tests() {
		if f, ok := reuse[t.ID]; ok && f != "" && f == prints[t.Package] {
			reused = append(reused, t.ID)
			continue
		}
		tests = append(tests, t)
	}
	if len(tests) == 0 {
		coverage, err := spoken(Reached{}, s.hello)
		if err != nil {
			return Error(id, "baseline_failed", "cannot write the coverage into the reni: "+err.Error(), true)
		}
		return BaselineDone(id, since(started), nil, coverage, nil, s.project.Differences(), reused)
	}
	ran, err := s.project.Run(ctx, tests, nil)
	if err != nil {
		return Error(id, "baseline_failed", err.Error(), true)
	}
	broken, isBroken := ran.Broken()
	timed := make([]Timed, 0, len(tests))
	var failures []Failure
	for _, t := range tests {
		timed = append(timed, Timed{Test: t.ID, File: t.File, DurationMS: elapsedMS(ran.elapsed[key(t.Package, t.Name)]), Fingerprint: prints[t.Package]})
		switch ran.verdict(t) {
		case "pass", "skip":
		case "fail":
			failures = append(failures, Failure{Test: t.ID, Message: firstOf(ran.message(t), "it failed")})
		default:
			why := "the test did not report a result"
			if isBroken {
				why = broken
			}
			failures = append(failures, Failure{Test: t.ID, Message: why})
		}
	}
	passed := map[string]bool{}
	for _, t := range tests {
		if v := ran.verdict(t); v == "pass" || v == "skip" {
			passed[t.ID] = true
		}
	}
	reached, withheld, err := s.project.Cover(ctx, tests, passed)
	if err != nil {
		return Error(id, "baseline_failed", err.Error(), true)
	}
	coverage := Coverage{Withheld: withheld}
	if withheld == "" {
		coverage, err = spoken(reached, s.hello)
		if err != nil {
			return Error(id, "baseline_failed", "cannot write the coverage into the reni: "+err.Error(), true)
		}
	}
	return BaselineDone(id, since(started), timed, coverage, failures, s.project.Differences(), reused)
}

// What a baseline says about coverage: inline when it is small, and packed
// in a file in the reni when it is not, which is every project big enough to
// be worth measuring.
func spoken(reached Reached, h *Hello) (Coverage, error) {
	files := make([]string, 0, len(reached))
	for f := range reached {
		files = append(files, f)
	}
	sort.Strings(files)
	entries := []Entry{}
	size := 0
	for _, f := range files {
		lines := make([]int, 0, len(reached[f]))
		for l := range reached[f] {
			lines = append(lines, l)
		}
		sort.Ints(lines)
		for _, l := range lines {
			e := Entry{File: f, Line: l, Tests: reached[f][l]}
			size += len(e.json()) + 1
			entries = append(entries, e)
		}
	}
	if int64(size) <= h.InlineLimitBytes {
		return Coverage{Inline: entries}, nil
	}
	path := filepath.Join(h.Reni, "coverage", fmt.Sprintf("%d.json", h.Worker))
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return Coverage{}, err
	}
	return Coverage{PackedPath: path}, os.WriteFile(path, []byte(packed(entries)), 0o644)
}

// The packed form: every test id once, and the lines of each file that the
// same tests reach, together.
func packed(entries []Entry) string {
	index := map[string]int{}
	var ids []string
	type group struct {
		file  string
		lines []int
		tests []int
	}
	var groups []*group
	byKey := map[string]*group{}
	for _, e := range entries {
		positions := make([]int, len(e.Tests))
		for i, t := range e.Tests {
			at, ok := index[t]
			if !ok {
				at = len(ids)
				index[t] = at
				ids = append(ids, t)
			}
			positions[i] = at
		}
		k := e.File + "\x00" + fmt.Sprint(positions)
		g := byKey[k]
		if g == nil {
			g = &group{file: e.File, tests: positions}
			byKey[k] = g
			groups = append(groups, g)
		}
		g.lines = append(g.lines, e.Line)
	}
	reached := make([]string, len(groups))
	for i, g := range groups {
		o := newObject().str("file", g.file)
		o.key("lines")
		o.b.WriteString(ints(g.lines))
		o.key("tests")
		o.b.WriteString(ints(g.tests))
		reached[i] = o.String()
	}
	return newObject().strs("tests", ids).objects("reached", reached).String() + "\n"
}

func ints(xs []int) string {
	parts := make([]string, len(xs))
	for i, x := range xs {
		parts[i] = strconv.Itoa(x)
	}
	return "[" + strings.Join(parts, ",") + "]"
}

func (s *Service) cast(ctx context.Context, id int64, c *CastRequest) Reply {
	asked := append(append([]string{}, c.Tests...), c.Rest...)
	tests := make([]Test, 0, len(asked))
	for _, a := range asked {
		t, ok := s.project.Lookup(a)
		if !ok {
			return say(Error(id, "unknown_test", "no test "+a, false))
		}
		tests = append(tests, t)
	}
	site := c.Site
	if site.End == nil {
		return say(Error(id, "bad_request", "the site has no end", false))
	}
	text, err := s.project.Read(site.File)
	if err != nil {
		return say(Error(id, "bad_request", fmt.Sprintf("cannot read `%s` in the reni: %v", site.File, err), false))
	}
	from, to := site.Start.Byte, site.End.Byte
	if from < 0 || to < from || to > len(text) || (site.Original != nil && text[from:to] != *site.Original) {
		return say(Error(id, "bad_request", fmt.Sprintf("`%s` is not the text this site was found in", site.File), false))
	}
	if ctx.Err() != nil {
		return Reply{Quiet: true}
	}
	started := time.Now()
	if len(tests) == 0 {
		return say(CastDone(id, c.Wekufe, Outcome{Kind: "no_coverage", Message: "no test was asked to judge it"}, since(started)))
	}
	overlay, err := s.project.Overlay(site.File, splice(text, from, to, site.Replacement))
	if err != nil {
		return say(Error(id, "cast_failed", "cannot write the wekufe beside the copy: "+err.Error(), true))
	}
	ran, err := s.project.Run(ctx, tests, s.project.Affected(site.File), overlay)
	if errors.Is(err, ErrAborted) {
		return Reply{Quiet: true}
	}
	if err != nil {
		return say(Error(id, "cast_failed", err.Error(), true))
	}
	outcome := judge(ran, tests)
	outcome.Message = s.project.Named(outcome.Message, site.File)
	return say(CastDone(id, c.Wekufe, outcome, since(started)))
}

// What a run of the tests says about a wekufe. A test binary that did not
// build is a compile error, whatever else ran; a test that failed, or that
// started and never finished because the wekufe took its binary down, is a
// kill; a run in which a test asked for never started is a crash.
func judge(ran *Ran, tests []Test) Outcome {
	if said, broken := ran.Broken(); broken {
		return Outcome{Kind: "compile_error", Message: said}
	}
	for _, t := range tests {
		if ran.verdict(t) == "fail" {
			return Outcome{Kind: "killed", KilledBy: t.ID}
		}
	}
	for _, t := range tests {
		if ran.verdict(t) == "" && ran.started[key(t.Package, t.Name)] {
			return Outcome{Kind: "killed", KilledBy: t.ID}
		}
	}
	missing := 0
	var died string
	for _, t := range tests {
		if ran.verdict(t) == "" {
			missing++
			if died == "" {
				if said := ran.Died(t.Package); said != "" {
					died = t.Package + ": " + said
				}
			}
		}
	}
	if missing > 0 {
		why := "the run ended"
		if ran.exit != nil {
			why = "the run ended with " + ran.exit.Error()
		}
		if died != "" {
			why += "; " + died
		}
		return Outcome{Kind: "crashed", Message: fmt.Sprintf("%d of %d selected test(s) ran: %s", len(tests)-missing, len(tests), why)}
	}
	return Outcome{Kind: "survived"}
}

func (s *Service) reload(id int64, files []string) string {
	started := time.Now()
	if err := s.project.CopyFiles(files); err != nil {
		return Error(id, "load_failed", "cannot copy the files into the reni: "+err.Error(), true)
	}
	return Reloaded(id, s.project.PackagesOf(files), since(started))
}

func (s *Service) sites(id int64, r *SitesRequest) string {
	var found []Site
	var skipped []Skipped
	for _, file := range r.Files {
		sites, skip := s.search(file, r)
		if skip != nil {
			skipped = append(skipped, *skip)
			continue
		}
		found = append(found, sites...)
	}
	return SitesFound(id, found, skipped)
}

func (s *Service) search(file string, r *SitesRequest) ([]Site, *Skipped) {
	skip := func(reason, message string) ([]Site, *Skipped) {
		return nil, &Skipped{File: file, Reason: reason, Message: message}
	}
	leave := func(reason, message string) ([]Site, *Skipped) {
		return nil, &Skipped{File: file, Reason: reason, Message: message, ByDesign: true}
	}
	if !strings.HasSuffix(file, ".go") {
		return leave("not_go", "`"+file+"` is not a Go source file")
	}
	if strings.HasSuffix(file, "_test.go") {
		return leave("test_file", "`"+file+"` is part of the suite, which is the oracle")
	}
	root := s.hello.Root
	if other := moduleOf(root, file); other != "" {
		return leave("other_module", "`"+file+"` belongs to the module at "+other+", not to this one")
	}
	dir, name := filepath.Split(filepath.Join(root, file))
	if ok, err := build.Default.MatchFile(dir, name); err != nil {
		return skip("unreadable", err.Error())
	} else if !ok {
		return skip("not_built", "`"+file+"` is not built here: its build constraints leave it out on "+build.Default.GOOS+"/"+build.Default.GOARCH)
	}
	text, err := os.ReadFile(filepath.Join(root, file))
	if err != nil {
		return skip("unreadable", err.Error())
	}
	sites, err := FindSites(file, string(text), r.Spells, r.ExcludeCalls)
	if errors.Is(err, ErrGenerated) {
		return leave("generated", err.Error())
	}
	if err != nil {
		return skip("parse_error", err.Error())
	}
	return sites, nil
}

// The directory of a nested module a file belongs to, relative to the root,
// or "" when it is the root module's.
func moduleOf(root, file string) string {
	dir := filepath.Dir(filepath.Clean(file))
	for dir != "." && dir != "/" && dir != "" {
		if _, err := os.Stat(filepath.Join(root, dir, "go.mod")); err == nil {
			return dir
		}
		dir = filepath.Dir(dir)
	}
	return ""
}
