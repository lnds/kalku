package kalku

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io/fs"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"sort"
	"strings"
)

// A fingerprint says what the tests of one package run: everything their
// test binary is compiled from, and what the package's directory holds for
// them to read. Two runs of a test whose fingerprint is the same build the
// same binary and give it the same files, so a baseline need not run the
// test again.
//
// A test binary holds the package, its tests, and every package they
// import. The module's own packages go in by their files: everything in each
// one's directory, and what it embeds from below it. Packages from elsewhere
// go in by go.mod and go.sum, which fix their versions, and the toolchain by
// its version and the settings that change what it builds. The tested
// package's whole directory goes in, testdata and all, because a test reads
// from there what it likes.
//
// What a test reads from outside its own package's directory is not in it.

// What changes how the toolchain builds, as `go env` says it. Paths that
// only say where things are on this machine are left out, so two checkouts
// of one module agree.
var building = []string{
	"GOVERSION", "GOOS", "GOARCH", "GOFLAGS", "GOEXPERIMENT", "GOTOOLCHAIN",
	"GOAMD64", "GOARM", "GOARM64", "GO386", "GOMIPS", "GOMIPS64", "GOPPC64", "GORISCV64", "GOWASM",
	"CGO_ENABLED", "CC", "CXX", "CGO_CFLAGS", "CGO_CPPFLAGS", "CGO_CXXFLAGS", "CGO_FFLAGS", "CGO_LDFLAGS",
}

// Fingerprints answers the fingerprint of every package of the module that
// has tests, by its import path.
func (p *Project) Fingerprints() (map[string]string, error) {
	base, err := p.toolchainPrint()
	if err != nil {
		return nil, err
	}
	embeds, err := p.embeds()
	if err != nil {
		return nil, err
	}
	dirOf := map[string]string{}
	for dir, pkg := range p.pkgOfDir {
		dirOf[pkg] = dir
	}
	files := &hashed{root: p.work, seen: map[string]string{}}
	own := map[string]string{}
	pkgPrint := func(pkg string) string {
		if h, ok := own[pkg]; ok {
			return h
		}
		h := files.of(append(files.in(dirOf[pkg]), embeds[pkg]...))
		own[pkg] = h
		return h
	}
	out := map[string]string{}
	for pkg, deps := range p.deps {
		dir, ok := dirOf[pkg]
		if !ok {
			continue
		}
		var mine []string
		for d := range deps {
			if _, inModule := dirOf[d]; inModule {
				mine = append(mine, d)
			}
		}
		sort.Strings(mine)
		h := sha256.New()
		h.Write([]byte(base))
		for _, d := range mine {
			h.Write([]byte("\n" + d + " " + pkgPrint(d)))
		}
		h.Write([]byte("\ntree " + files.of(files.under(dir))))
		out[pkg] = hex.EncodeToString(h.Sum(nil))[:24]
	}
	return out, nil
}

// The toolchain and the versions of everything from outside the module.
func (p *Project) toolchainPrint() (string, error) {
	cmd := exec.Command("go", "env", "-json")
	cmd.Dir, cmd.Env = p.work, p.env
	out, err := cmd.Output()
	if err != nil {
		return "", err
	}
	var env map[string]string
	if err := json.Unmarshal(out, &env); err != nil {
		return "", err
	}
	h := sha256.New()
	for _, k := range building {
		h.Write([]byte(k + "=" + env[k] + "\n"))
	}
	for _, f := range []string{"go.mod", "go.sum"} {
		b, err := os.ReadFile(filepath.Join(p.work, f))
		if err != nil && !os.IsNotExist(err) {
			return "", err
		}
		h.Write([]byte(f + "\n"))
		h.Write(b)
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// What each package embeds, as paths relative to the root: a package can
// embed files from below its directory that belong to no package.
func (p *Project) embeds() (map[string][]string, error) {
	out, err := p.goList("-f", "{{.ImportPath}}\t{{.Dir}}{{range .EmbedFiles}}\t{{.}}{{end}}{{range .TestEmbedFiles}}\t{{.}}{{end}}{{range .XTestEmbedFiles}}\t{{.}}{{end}}", "./...")
	if err != nil {
		return nil, err
	}
	embeds := map[string][]string{}
	for _, l := range strings.Split(strings.TrimSpace(out), "\n") {
		parts := strings.Split(l, "\t")
		if len(parts) < 3 {
			continue
		}
		dir := p.relative(parts[1])
		for _, f := range parts[2:] {
			embeds[parts[0]] = append(embeds[parts[0]], path.Join(dir, f))
		}
	}
	return embeds, nil
}

// hashed hashes the files of the copy, each once.
type hashed struct {
	root string
	seen map[string]string
}

// The files directly in a directory, relative to the root.
func (h *hashed) in(dir string) []string {
	entries, _ := os.ReadDir(filepath.Join(h.root, dir))
	var out []string
	for _, e := range entries {
		if e.Type().IsRegular() {
			out = append(out, path.Join(dir, e.Name()))
		}
	}
	return out
}

// What git and kalku keep of their own, which no test is built from and
// which every run of kalku writes to.
var leftOut = map[string]bool{".git": true, ".kalku": true}

// Every file under a directory, relative to the root, leaving out modules
// of their own below it and what git and kalku keep.
func (h *hashed) under(dir string) []string {
	var out []string
	top := filepath.Join(h.root, dir)
	_ = filepath.WalkDir(top, func(at string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() && at != top {
			if leftOut[d.Name()] {
				return filepath.SkipDir
			}
			if _, err := os.Stat(filepath.Join(at, "go.mod")); err == nil {
				return filepath.SkipDir
			}
		}
		if d.Type().IsRegular() {
			rel, _ := filepath.Rel(h.root, at)
			out = append(out, filepath.ToSlash(rel))
		}
		return nil
	})
	return out
}

// One hash of these files, by their names and what is in them.
func (h *hashed) of(files []string) string {
	files = append([]string{}, files...)
	sort.Strings(files)
	sum := sha256.New()
	for _, f := range files {
		sum.Write([]byte(f + " " + h.file(f) + "\n"))
	}
	return hex.EncodeToString(sum.Sum(nil))
}

func (h *hashed) file(f string) string {
	if s, ok := h.seen[f]; ok {
		return s
	}
	b, err := os.ReadFile(filepath.Join(h.root, f))
	s := "unreadable"
	if err == nil {
		sum := sha256.Sum256(b)
		s = hex.EncodeToString(sum[:])
	}
	h.seen[f] = s
	return s
}
