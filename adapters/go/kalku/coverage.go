package kalku

import (
	"bufio"
	"context"
	"fmt"
	"go/ast"
	"go/build"
	"go/parser"
	"go/token"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"syscall"
)

// Coverage per test comes from Go's own cover tool: each package's test
// binary is built once with `-cover -coverpkg=./...`, and run once for each
// of its tests, alone, with a profile of its own.
//
// A profile counts blocks, and a block is not a line: a `case` expression
// sits before the block of its clause and is run by tests that never enter
// it, and an `else if` condition is run by every test that reaches the
// `if`. So a test is credited with every line of each function it entered,
// which is a superset and never misses one. What is declared outside any
// function — a package's variables and constants — runs as the package is
// set up, and is credited to every test whose binary holds the package.

// Reached is which tests reach each line, by file.
type Reached map[string]map[int][]string

// region is a span of lines of one file, credited as one.
type region struct {
	file     string
	from, to int
	pkg      string
	function bool
	tests    map[string]bool
}

// Cover runs every test alone on a binary built for coverage, and answers
// which tests reach which lines. A test that passes in the suite and fails
// alone is answered as the reason the coverage cannot be trusted.
func (p *Project) Cover(ctx context.Context, tests []Test, passed map[string]bool) (Reached, string, error) {
	regions, err := p.regions()
	if err != nil {
		return nil, "", err
	}
	dir := filepath.Join(p.reni, "cover", filepath.Base(p.overlay))
	if err := os.RemoveAll(dir); err != nil {
		return nil, "", err
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, "", err
	}
	byPkg := map[string][]Test{}
	var order []string
	for _, t := range tests {
		if _, ok := byPkg[t.Package]; !ok {
			order = append(order, t.Package)
		}
		byPkg[t.Package] = append(byPkg[t.Package], t)
	}
	for n, pkg := range order {
		binary := filepath.Join(dir, strconv.Itoa(n)+".test")
		build := exec.Command("go", "test", "-c", "-cover", "-covermode=set", "-coverpkg=./...", "-vet=off", "-o", binary, pkg)
		if out, err := p.grouped(ctx, build, p.work); err != nil {
			if ctx.Err() != nil {
				return nil, "", ErrAborted
			}
			return nil, fmt.Sprintf("the tests of %s did not build for coverage: %s", pkg, firstOf(out, err.Error())), nil
		}
		pkgDir := p.dirOf(pkg)
		for _, t := range byPkg[pkg] {
			profile := filepath.Join(dir, "profile.out")
			// A test that fails alone is asked again before anything is
			// concluded: a flaky one passes, and what it reached then is
			// what it reaches. One that never does needs another test first.
			var out string
			var err error
			for try := 0; try < aloneTries; try++ {
				_ = os.Remove(profile)
				run := exec.Command(binary, "-test.run=^"+t.Name+"$", "-test.coverprofile="+profile, "-test.paniconexit0", "-test.timeout=10m")
				out, err = p.grouped(ctx, run, pkgDir)
				if ctx.Err() != nil {
					return nil, "", ErrAborted
				}
				if err == nil || !passed[t.ID] {
					break
				}
			}
			if err != nil && passed[t.ID] {
				return nil, fmt.Sprintf("%s passes in the suite and failed each of %d runs alone, so what it reaches alone is not what it reaches there: %s", t.ID, aloneTries, lastLine(out)), nil
			}
			blocks, err := p.readProfile(profile)
			if err != nil {
				return nil, fmt.Sprintf("the coverage profile of %s could not be read: %v", t.ID, err), nil
			}
			for _, b := range blocks {
				for _, r := range regions[b.file] {
					if r.function && r.from <= b.line && b.line <= r.to {
						r.tests[t.ID] = true
					}
				}
			}
		}
	}
	// What is declared outside a function is set up in every binary that
	// holds its package.
	for _, rs := range regions {
		for _, r := range rs {
			if r.function {
				continue
			}
			for _, t := range tests {
				if t.Package == r.pkg || p.deps[t.Package][r.pkg] {
					r.tests[t.ID] = true
				}
			}
		}
	}
	reached := Reached{}
	for file, rs := range regions {
		for _, r := range rs {
			if len(r.tests) == 0 {
				continue
			}
			ids := make([]string, 0, len(r.tests))
			for id := range r.tests {
				ids = append(ids, id)
			}
			sort.Strings(ids)
			if reached[file] == nil {
				reached[file] = map[int][]string{}
			}
			for line := r.from; line <= r.to; line++ {
				reached[file][line] = union(reached[file][line], ids)
			}
		}
	}
	return reached, "", nil
}

func union(a, b []string) []string {
	if len(a) == 0 {
		return b
	}
	seen := map[string]bool{}
	var out []string
	for _, s := range append(append([]string{}, a...), b...) {
		if !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	sort.Strings(out)
	return out
}

// How many times a test is run alone before it is taken to need another.
const aloneTries = 3

// What a test said as it failed: the last line that is neither the runner's
// verdict nor its coverage.
func lastLine(out string) string {
	lines := strings.Split(strings.TrimSpace(out), "\n")
	for i := len(lines) - 1; i >= 0; i-- {
		l := strings.TrimSpace(lines[i])
		if l != "" && l != "FAIL" && !strings.HasPrefix(l, "exit status") && !strings.HasPrefix(l, "coverage:") {
			return l
		}
	}
	return "it exited with an error"
}

// The functions and the package-level declarations of every source file of
// the module, keyed by its path relative to the root.
func (p *Project) regions() (map[string][]*region, error) {
	out := map[string][]*region{}
	for dir, pkg := range p.pkgOfDir {
		entries, err := os.ReadDir(filepath.Join(p.work, dir))
		if err != nil {
			return nil, err
		}
		for _, e := range entries {
			name := e.Name()
			if !strings.HasSuffix(name, ".go") || strings.HasSuffix(name, "_test.go") {
				continue
			}
			if ok, err := build.Default.MatchFile(filepath.Join(p.work, dir), name); err != nil || !ok {
				continue
			}
			file := path.Join(dir, name)
			if dir == "." {
				file = name
			}
			fset := token.NewFileSet()
			f, err := parser.ParseFile(fset, filepath.Join(p.work, dir, name), nil, parser.SkipObjectResolution)
			if err != nil {
				continue
			}
			line := func(pos token.Pos) int { return fset.Position(pos).Line }
			for _, d := range f.Decls {
				switch d := d.(type) {
				case *ast.FuncDecl:
					if d.Body != nil {
						out[file] = append(out[file], &region{file: file, from: line(d.Pos()), to: line(d.End()), pkg: pkg, function: true, tests: map[string]bool{}})
					}
				case *ast.GenDecl:
					if d.Tok == token.VAR || d.Tok == token.CONST {
						out[file] = append(out[file], &region{file: file, from: line(d.Pos()), to: line(d.End()), pkg: pkg, tests: map[string]bool{}})
					}
				}
			}
		}
	}
	return out, nil
}

func (p *Project) dirOf(pkg string) string {
	for dir, ip := range p.pkgOfDir {
		if ip == pkg {
			return filepath.Join(p.work, dir)
		}
	}
	return p.work
}

type block struct {
	file string
	line int
}

// The first line of every block a profile says ran, by the file's path
// relative to the root. Files of other modules are left out.
func (p *Project) readProfile(profile string) ([]block, error) {
	f, err := os.Open(profile)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	var out []block
	s := bufio.NewScanner(f)
	for s.Scan() {
		l := s.Text()
		if strings.HasPrefix(l, "mode:") {
			continue
		}
		// example.com/m/a/b.go:12.3,14.2 1 1
		colon := strings.LastIndex(l, ":")
		fields := strings.Fields(l[colon+1:])
		if colon < 0 || len(fields) != 3 || fields[2] == "0" {
			continue
		}
		rel, ok := strings.CutPrefix(l[:colon], p.module+"/")
		if !ok {
			continue
		}
		start, _, _ := strings.Cut(fields[0], ".")
		n, err := strconv.Atoi(start)
		if err != nil {
			continue
		}
		out = append(out, block{file: rel, line: n})
	}
	return out, s.Err()
}

// grouped runs a command in a process group of its own, with the project's
// environment, and answers what it printed.
func (p *Project) grouped(ctx context.Context, cmd *exec.Cmd, dir string) (string, error) {
	cmd.Dir, cmd.Env = dir, p.env
	out := &capped{max: 64 * 1024, pass: os.Stderr}
	cmd.Stdout, cmd.Stderr = out, out
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	groupsMu.Lock()
	if err := cmd.Start(); err != nil {
		groupsMu.Unlock()
		return "", err
	}
	pgid := cmd.Process.Pid
	groups[pgid] = true
	groupsMu.Unlock()
	done := make(chan struct{})
	go func() {
		select {
		case <-ctx.Done():
			_ = syscall.Kill(-pgid, syscall.SIGKILL)
		case <-done:
		}
	}()
	err := cmd.Wait()
	close(done)
	groupsMu.Lock()
	delete(groups, pgid)
	groupsMu.Unlock()
	return out.String(), err
}
