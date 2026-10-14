package kalku

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"io"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Project is the module a `hello` names, copied into the reni and built and
// tested there. The user's tree is only ever read; a wekufe is handed to
// `go test` through `-overlay` and never written to a file the copy holds,
// so whatever ends a cast leaves the copy as it was.
type Project struct {
	root    string
	reni    string
	work    string // the copy
	overlay string // where a wekufe's file is written, beside the copy
	cache   string // GOCACHE
	env     []string
	module  string // the module path
	tests   []Test
	byID    map[string]int
	// A package's directory, relative to the root, to its import path.
	pkgOfDir map[string]string
	// A package to every package its test binary is built from.
	deps map[string]map[string]bool
}

// Test is one test of the suite: a top-level `Test`, `Example` or `Fuzz`
// function of one package.
type Test struct {
	ID      string
	Package string // the import path
	Name    string
	File    string // relative to the root; the package's directory when not found
}

// What a build needs from the environment of whoever started the kalku;
// everything else is left out, so a test run sees only what it was given.
var inherited = []string{
	"PATH", "HOME", "USER", "TMPDIR", "LANG",
	"GOROOT", "GOPATH", "GOMODCACHE", "GOENV", "GOTOOLCHAIN", "GOFLAGS",
	"GOPROXY", "GOPRIVATE", "GONOPROXY", "GONOSUMDB", "GOSUMDB", "GOINSECURE",
	"CGO_ENABLED", "CC", "CXX", "PKG_CONFIG_PATH",
}

// NewProject is the project of a `hello`.
func NewProject(h *Hello) *Project {
	var env []string
	for _, k := range inherited {
		if v, ok := os.LookupEnv(k); ok {
			env = append(env, k+"="+v)
		}
	}
	cache := filepath.Join(h.Reni, "gocache")
	env = append(env, "GOCACHE="+cache, "GOWORK=off")
	keys := make([]string, 0, len(h.Env))
	for k := range h.Env {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		env = append(env, k+"="+h.Env[k])
	}
	worker := fmt.Sprint(h.Worker)
	return &Project{
		root:    h.Root,
		reni:    h.Reni,
		work:    filepath.Join(h.Reni, "work", worker),
		overlay: filepath.Join(h.Reni, "overlay", worker),
		cache:   cache,
		env:     env,
		byID:    map[string]int{},
	}
}

// Copy puts the module's files in the copy, without what git keeps and
// without the reni, in case it is inside the project. A file that is the
// same is left alone; what the module no longer has is removed.
//
// The copy is known by its path with every link resolved, because that is
// the path `go` works in and the one `-overlay` must name: on macOS a
// temporary directory is under `/var`, itself a link to `/private/var`, and
// an overlay for the other spelling of the same file is silently ignored.
func (p *Project) Copy() error {
	if err := syncTree(p.root, p.work, p.reni); err != nil {
		return err
	}
	resolved, err := filepath.EvalSymlinks(p.work)
	if err != nil {
		return err
	}
	p.work = resolved
	return nil
}

// CopyFiles brings these files of the module into the copy again.
func (p *Project) CopyFiles(files []string) error {
	for _, f := range files {
		from, to := filepath.Join(p.root, f), filepath.Join(p.work, f)
		data, err := os.ReadFile(from)
		if errors.Is(err, os.ErrNotExist) {
			if err := os.Remove(to); err != nil && !errors.Is(err, os.ErrNotExist) {
				return err
			}
			continue
		}
		if err != nil {
			return err
		}
		if err := os.MkdirAll(filepath.Dir(to), 0o755); err != nil {
			return err
		}
		if err := os.WriteFile(to, data, 0o644); err != nil {
			return err
		}
	}
	return nil
}

func syncTree(from, to, reni string) error {
	if err := os.MkdirAll(to, 0o755); err != nil {
		return err
	}
	entries, err := os.ReadDir(from)
	if err != nil {
		return err
	}
	wanted := map[string]bool{}
	for _, e := range entries {
		name := e.Name()
		src := filepath.Join(from, name)
		if name == ".git" || src == reni {
			continue
		}
		wanted[name] = true
		dst := filepath.Join(to, name)
		switch {
		case e.Type()&os.ModeSymlink != 0:
			target, err := os.Readlink(src)
			if err != nil {
				return err
			}
			if old, err := os.Readlink(dst); err != nil || old != target {
				if err := os.RemoveAll(dst); err != nil {
					return err
				}
				if err := os.Symlink(target, dst); err != nil {
					return err
				}
			}
		case e.IsDir():
			if info, err := os.Lstat(dst); err == nil && !info.IsDir() {
				if err := os.RemoveAll(dst); err != nil {
					return err
				}
			}
			if err := syncTree(src, dst, reni); err != nil {
				return err
			}
		case e.Type().IsRegular():
			data, err := os.ReadFile(src)
			if err != nil {
				return err
			}
			if old, err := os.ReadFile(dst); err == nil && bytes.Equal(old, data) {
				continue
			}
			if info, err := os.Lstat(dst); err == nil && !info.Mode().IsRegular() {
				if err := os.RemoveAll(dst); err != nil {
					return err
				}
			}
			info, err := e.Info()
			if err != nil {
				return err
			}
			if err := os.WriteFile(dst, data, info.Mode().Perm()); err != nil {
				return err
			}
		}
	}
	have, err := os.ReadDir(to)
	if err != nil {
		return err
	}
	for _, e := range have {
		if !wanted[e.Name()] {
			if err := os.RemoveAll(filepath.Join(to, e.Name())); err != nil {
				return err
			}
		}
	}
	return nil
}

// Read is a file of the copy.
func (p *Project) Read(file string) (string, error) {
	data, err := os.ReadFile(filepath.Join(p.work, file))
	return string(data), err
}

// Event is one line of `go test -json`.
type Event struct {
	Action      string
	Package     string
	ImportPath  string
	Test        string
	Elapsed     float64
	Output      string
	FailedBuild string
}

// Ran is what one run of `go test` said: the verdict of each top-level test
// it reported, the packages that did not build, and how the run ended.
type Ran struct {
	verdicts map[string]string // package + "\x00" + test → pass, fail, skip
	started  map[string]bool
	elapsed  map[string]float64
	output   map[string]*strings.Builder
	broken   map[string]bool             // packages whose test binary did not build
	list     [][2]string                 // what `-list` printed
	said     map[string]*strings.Builder // what a package's binary printed outside any test
	build    strings.Builder             // what the build said
	stderr   *capped
	exit     error
}

func key(pkg, test string) string { return pkg + "\x00" + test }

func (r *Ran) verdict(t Test) string { return r.verdicts[key(t.Package, t.Name)] }

func (r *Ran) message(t Test) string {
	if b := r.output[key(t.Package, t.Name)]; b != nil {
		return strings.TrimSpace(b.String())
	}
	return ""
}

// Broken is what the build said, for a run whose test binaries did not all
// build.
func (r *Ran) Broken() (string, bool) {
	if len(r.broken) == 0 {
		return "", false
	}
	said := strings.TrimSpace(r.build.String())
	if said == "" {
		said = strings.TrimSpace(r.stderr.String())
	}
	if said == "" {
		pkgs := make([]string, 0, len(r.broken))
		for p := range r.broken {
			pkgs = append(pkgs, p)
		}
		sort.Strings(pkgs)
		said = "the tests of " + strings.Join(pkgs, ", ") + " did not build"
	}
	return said, true
}

func (r *Ran) read(out io.Reader) {
	s := bufio.NewScanner(out)
	s.Buffer(make([]byte, 64*1024), MaxLine)
	for s.Scan() {
		var e Event
		if json.Unmarshal(s.Bytes(), &e) != nil {
			r.plain(s.Text())
			continue
		}
		r.add(e)
	}
}

// Before Go 1.24 a package whose tests did not build is said on stdout in
// plain text, `FAIL\tpkg [build failed]`, and not as an event.
func (r *Ran) plain(line string) {
	rest, ok := strings.CutPrefix(line, "FAIL\t")
	if !ok {
		return
	}
	for _, mark := range []string{" [build failed]", " [setup failed]"} {
		if pkg, found := strings.CutSuffix(rest, mark); found {
			r.broken[pkg] = true
		}
	}
}

func (r *Ran) add(e Event) {
	switch {
	case e.Action == "build-output":
		r.build.WriteString(e.Output)
	case e.Action == "build-fail":
		r.broken[e.ImportPath] = true
	case e.Test == "":
		if name := strings.TrimSpace(e.Output); e.Action == "output" && testName.MatchString(name) {
			r.list = append(r.list, [2]string{e.Package, name})
		}
		if e.Action == "output" {
			b := r.said[e.Package]
			if b == nil {
				b = &strings.Builder{}
				r.said[e.Package] = b
			}
			if b.Len() < 64*1024 {
				b.WriteString(e.Output)
			}
		}
		if e.FailedBuild != "" {
			r.broken[e.Package] = true
		}
		if e.Action == "output" && (strings.Contains(e.Output, "[build failed]") || strings.Contains(e.Output, "[setup failed]")) {
			r.broken[e.Package] = true
		}
	case strings.Contains(e.Test, "/"):
		// A subtest is part of the test that ran it, which is what is judged.
		top := e.Test[:strings.Index(e.Test, "/")]
		if e.Action == "output" {
			r.out(e.Package, top).WriteString(e.Output)
		}
	default:
		k := key(e.Package, e.Test)
		switch e.Action {
		case "run":
			r.started[k] = true
		case "output":
			r.out(e.Package, e.Test).WriteString(e.Output)
		case "pass", "fail", "skip":
			r.verdicts[k] = e.Action
			r.elapsed[k] = e.Elapsed
		}
	}
}

func (r *Ran) out(pkg, test string) *strings.Builder {
	k := key(pkg, test)
	b := r.output[k]
	if b == nil {
		b = &strings.Builder{}
		r.output[k] = b
	}
	return b
}

// Died is why a package's test binary ended without running its tests, as
// it said it: the line that begins its panic or its fatal error, or else
// the last thing it printed. Empty when it said nothing.
func (r *Ran) Died(pkg string) string {
	b := r.said[pkg]
	if b == nil {
		return ""
	}
	var last string
	for _, l := range strings.Split(b.String(), "\n") {
		l = strings.TrimSpace(l)
		if strings.HasPrefix(l, "panic:") || strings.HasPrefix(l, "fatal error:") {
			return l
		}
		if l != "" && !strings.HasPrefix(l, "FAIL") && !strings.HasPrefix(l, "exit status") && !strings.HasPrefix(l, "ok ") {
			last = l
		}
	}
	return last
}

// capped keeps the first bytes written to it, and passes all of them on.
type capped struct {
	mu   sync.Mutex
	buf  bytes.Buffer
	max  int
	pass io.Writer
}

func (c *capped) Write(b []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if room := c.max - c.buf.Len(); room > 0 {
		c.buf.Write(b[:min(room, len(b))])
	}
	return c.pass.Write(b)
}

func (c *capped) String() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.buf.String()
}

// The process groups of the commands under way, so that a kalku whose run
// is gone can end them all.
var (
	groupsMu sync.Mutex
	groups   = map[int]bool{}
)

// Leave ends every command under way, with everything it started, and then
// this process. Held to the end, so that no command starts after the ones
// under way were killed.
func Leave(code int) {
	groupsMu.Lock()
	for g := range groups {
		_ = syscall.Kill(-g, syscall.SIGKILL)
	}
	os.Exit(code)
}

// ErrAborted is a command that was stopped because its context was.
var ErrAborted = errors.New("aborted")

// goTest runs `go test` in the copy with these arguments. The command gets
// a process group of its own, because a test binary is a child of `go`, and
// ending `go` alone would leave it running.
func (p *Project) goTest(ctx context.Context, args ...string) (*Ran, error) {
	cmd := exec.Command("go", append([]string{"test", "-json", "-vet=off"}, args...)...)
	cmd.Dir = p.work
	cmd.Env = p.env
	// stdin is the protocol: a test that read it would eat requests.
	cmd.Stdin = nil
	r := newRan(os.Stderr)
	cmd.Stderr = r.stderr
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	out, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	groupsMu.Lock()
	if err := cmd.Start(); err != nil {
		groupsMu.Unlock()
		return nil, err
	}
	pgid := cmd.Process.Pid
	groups[pgid] = true
	groupsMu.Unlock()

	read := make(chan struct{})
	go func() {
		r.read(out)
		close(read)
	}()
	stopped := make(chan struct{})
	go func() {
		select {
		case <-ctx.Done():
			_ = syscall.Kill(-pgid, syscall.SIGKILL)
		case <-stopped:
		}
	}()
	<-read
	r.exit = cmd.Wait()
	close(stopped)
	groupsMu.Lock()
	delete(groups, pgid)
	groupsMu.Unlock()
	if ctx.Err() != nil {
		return nil, ErrAborted
	}
	return r, nil
}

// goList runs `go list` in the copy and answers what it printed.
func (p *Project) goList(args ...string) (string, error) {
	cmd := exec.Command("go", append([]string{"list"}, args...)...)
	cmd.Dir = p.work
	cmd.Env = p.env
	var out, errs bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &errs
	if err := cmd.Run(); err != nil {
		return "", fmt.Errorf("go list: %s", strings.TrimSpace(errs.String()))
	}
	return out.String(), nil
}

// Build compiles every test binary of the module and lists the tests in
// them, which is what `prepare` leaves for a baseline and the casts. The
// packages are the module's own, as `./...` finds them.
func (p *Project) Build(ctx context.Context) (int, error) {
	module, err := p.goList("-m")
	if err != nil {
		return 0, err
	}
	p.module = strings.TrimSpace(module)
	dirs, err := p.goList("-f", "{{.ImportPath}}\t{{.Dir}}", "./...")
	if err != nil {
		return 0, err
	}
	dirOf := map[string]string{}
	p.pkgOfDir = map[string]string{}
	for _, l := range strings.Split(strings.TrimSpace(dirs), "\n") {
		if pkg, dir, ok := strings.Cut(l, "\t"); ok {
			dirOf[pkg] = dir
			p.pkgOfDir[p.relative(dir)] = pkg
		}
	}
	if err := p.readDeps(); err != nil {
		return 0, err
	}
	ran, err := p.goTest(ctx, "-count=1", "-list", ".", "./...")
	if err != nil {
		return 0, err
	}
	if said, broken := ran.Broken(); broken {
		return 0, &BuildFailed{Message: said}
	}
	if ran.exit != nil && len(ran.listed()) == 0 {
		return 0, &BuildFailed{Message: firstOf(ran.stderr.String(), "go test could not list the tests: "+ran.exit.Error())}
	}
	p.tests, p.byID = nil, map[string]int{}
	// Packages are listed as they finish, which is any order; the suite is
	// in the order of its packages, each package's tests as it lists them.
	listed := ran.listed()
	sort.SliceStable(listed, func(i, j int) bool { return listed[i][0] < listed[j][0] })
	for _, pt := range listed {
		file := p.fileOf(dirOf[pt[0]], pt[1])
		if file == "" {
			file = p.relative(dirOf[pt[0]])
		}
		t := Test{ID: file + ":" + pt[1], Package: pt[0], Name: pt[1], File: file}
		p.byID[t.ID] = len(p.tests)
		p.tests = append(p.tests, t)
	}
	return len(dirOf), nil
}

// BuildFailed is a module whose tests do not build.
type BuildFailed struct{ Message string }

func (e *BuildFailed) Error() string { return e.Message }

func firstOf(texts ...string) string {
	for _, t := range texts {
		if t = strings.TrimSpace(t); t != "" {
			return t
		}
	}
	return ""
}

var testName = regexp.MustCompile(`^(Test|Example|Fuzz)[\p{L}\p{N}_]*$`)

// The tests `-list` printed, as (package, name), in the order it printed
// them. Benchmarks are left out: `go test` does not run them unless asked.
func (r *Ran) listed() [][2]string { return r.list }

func (p *Project) relative(dir string) string {
	rel, err := filepath.Rel(p.work, dir)
	if err != nil {
		return dir
	}
	return filepath.ToSlash(rel)
}

// The file of a package's directory that declares a test function.
func (p *Project) fileOf(dir, name string) string {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return ""
	}
	for _, e := range entries {
		if !strings.HasSuffix(e.Name(), "_test.go") {
			continue
		}
		f, err := parser.ParseFile(token.NewFileSet(), filepath.Join(dir, e.Name()), nil, parser.SkipObjectResolution)
		if err != nil {
			continue
		}
		for _, d := range f.Decls {
			if fd, ok := d.(*ast.FuncDecl); ok && fd.Recv == nil && fd.Name.Name == name {
				return path.Join(p.relative(dir), e.Name())
			}
		}
	}
	return ""
}

// Tests is the suite, as `prepare` found it.
func (p *Project) Tests() []Test { return p.tests }

// Lookup is a test by its id.
func (p *Project) Lookup(id string) (Test, bool) {
	i, ok := p.byID[id]
	if !ok {
		return Test{}, false
	}
	return p.tests[i], true
}

// Run runs these tests. A package whose tests are all asked for runs with
// the other such packages in one `go test`, and one asked for in part runs
// on its own, so that `-run` names its tests and no other package's.
//
// A package in `fresh` runs for real. One that is not, when `fresh` is not
// nil, is a package whose test binary is the one an earlier run built, so
// `go test` may answer from its own cache, which it keeps by the binary and
// what the tests read. A nil `fresh` runs everything for real.
func (p *Project) Run(ctx context.Context, tests []Test, fresh map[string]bool, extra ...string) (*Ran, error) {
	byPkg := map[string][]string{}
	var order []string
	for _, t := range tests {
		if _, ok := byPkg[t.Package]; !ok {
			order = append(order, t.Package)
		}
		byPkg[t.Package] = append(byPkg[t.Package], t.Name)
	}
	count := map[string]int{}
	for _, t := range p.tests {
		count[t.Package]++
	}
	isFresh := func(pkg string) bool { return fresh == nil || fresh[pkg] }
	flags := func(pkg string) []string {
		args := append([]string{}, extra...)
		if isFresh(pkg) {
			args = append(args, "-count=1")
		}
		return args
	}
	all := newRan(io.Discard)
	var whole [2][]string // cached, fresh
	for _, pkg := range order {
		if len(byPkg[pkg]) == count[pkg] {
			i := 0
			if isFresh(pkg) {
				i = 1
			}
			whole[i] = append(whole[i], pkg)
		}
	}
	for _, pkgs := range whole {
		if len(pkgs) == 0 {
			continue
		}
		ran, err := p.goTest(ctx, append(flags(pkgs[0]), pkgs...)...)
		if err != nil {
			return nil, err
		}
		all.merge(ran)
	}
	for _, pkg := range order {
		if len(byPkg[pkg]) == count[pkg] {
			continue
		}
		run := "^(" + strings.Join(byPkg[pkg], "|") + ")$"
		ran, err := p.goTest(ctx, append(flags(pkg), "-run", run, pkg)...)
		if err != nil {
			return nil, err
		}
		all.merge(ran)
	}
	return all, nil
}

func newRan(stderr io.Writer) *Ran {
	return &Ran{
		verdicts: map[string]string{},
		started:  map[string]bool{},
		elapsed:  map[string]float64{},
		output:   map[string]*strings.Builder{},
		broken:   map[string]bool{},
		said:     map[string]*strings.Builder{},
		stderr:   &capped{max: 64 * 1024, pass: stderr},
	}
}

// Affected is the packages whose test binaries hold the package of a file,
// and so are built anew when the file changes; nil when the file is in no
// package `prepare` found, so that everything runs for real.
func (p *Project) Affected(file string) map[string]bool {
	pkg, ok := p.pkgOfDir[path.Dir(file)]
	if !ok {
		return nil
	}
	out := map[string]bool{pkg: true}
	for tested, deps := range p.deps {
		if deps[pkg] {
			out[tested] = true
		}
	}
	return out
}

// PackagesOf is the import paths of the packages these files are in.
func (p *Project) PackagesOf(files []string) []string {
	seen := map[string]bool{}
	out := []string{}
	for _, f := range files {
		if pkg, ok := p.pkgOfDir[path.Dir(f)]; ok && !seen[pkg] {
			seen[pkg] = true
			out = append(out, pkg)
		}
	}
	return out
}

// What each package's test binary is built from, every package it holds
// with the variants built for the test named as the package itself.
func (p *Project) readDeps() error {
	out, err := p.goList("-test", "-f", "{{.ImportPath}}\t{{join .Deps \" \"}}", "./...")
	if err != nil {
		return err
	}
	p.deps = map[string]map[string]bool{}
	for _, l := range strings.Split(strings.TrimSpace(out), "\n") {
		main, deps, ok := strings.Cut(l, "\t")
		if !ok || !strings.HasSuffix(main, ".test") {
			continue
		}
		set := map[string]bool{}
		for _, d := range strings.Fields(deps) {
			if base, _, variant := strings.Cut(d, " ["); variant {
				d = base
			}
			set[d] = true
		}
		p.deps[strings.TrimSuffix(main, ".test")] = set
	}
	return nil
}

func (r *Ran) merge(o *Ran) {
	for k, v := range o.verdicts {
		r.verdicts[k] = v
	}
	for k, v := range o.started {
		r.started[k] = v
	}
	for k, v := range o.elapsed {
		r.elapsed[k] = v
	}
	for k, v := range o.output {
		r.output[k] = v
	}
	for k := range o.broken {
		r.broken[k] = true
	}
	for k, v := range o.said {
		r.said[k] = v
	}
	r.build.WriteString(o.build.String())
	_, _ = r.stderr.Write([]byte(o.stderr.String()))
	if o.exit != nil {
		r.exit = o.exit
	}
}

// Overlay writes a wekufe's file beside the copy and answers the `-overlay`
// flag that hands it to `go test` in place of the copy's own.
func (p *Project) Overlay(file, text string) (string, error) {
	if err := os.MkdirAll(p.overlay, 0o755); err != nil {
		return "", err
	}
	spliced := filepath.Join(p.overlay, "wekufe.go")
	if err := os.WriteFile(spliced, []byte(text), 0o644); err != nil {
		return "", err
	}
	replace, err := json.Marshal(map[string]map[string]string{
		"Replace": {filepath.Join(p.work, file): spliced},
	})
	if err != nil {
		return "", err
	}
	index := filepath.Join(p.overlay, "overlay.json")
	if err := os.WriteFile(index, replace, 0o644); err != nil {
		return "", err
	}
	return "-overlay=" + index, nil
}

// Named says a message about a wekufe with the file's own name where `go`
// named the file the overlay put in its place.
func (p *Project) Named(message, file string) string {
	written := regexp.MustCompile(`[^\s:"']*` + regexp.QuoteMeta(filepath.Base(p.overlay)+"/wekufe.go"))
	return written.ReplaceAllLiteralString(message, file)
}

// Differences are how a run here is unlike `go test ./...` at the root of
// the module. A suite that is green there and red here is red for one of
// these.
func (p *Project) Differences() []string {
	passed := make([]string, 0, len(p.env))
	for _, kv := range p.env {
		k, _, _ := strings.Cut(kv, "=")
		passed = append(passed, k)
	}
	return []string{
		fmt.Sprintf("it ran in a copy of the module kept in the reni, %s: what the copy leaves out is not there, `.git`", p.work),
		fmt.Sprintf("the build cache is %s (GOCACHE), not yours", p.cache),
		"vet did not run (`-vet=off`): a test run here does not fail for what vet would say",
		"a go.work around the module was not used (GOWORK=off): the module's own go.mod chose every dependency",
		fmt.Sprintf("the tests see none of your environment but %s: any other variable set for them in your shell is not there", strings.Join(passed, ", ")),
	}
}

func elapsedMS(seconds float64) int64 { return int64(seconds*1000 + 0.5) }

func since(t time.Time) int64 { return time.Since(t).Milliseconds() }
