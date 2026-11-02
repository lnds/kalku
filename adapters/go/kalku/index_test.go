package kalku

import (
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func write(t *testing.T, dir, name, text string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, name), []byte(text), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestATestIsInTheFirstTestFileThatDeclaresIt(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "p")
	if err := os.Mkdir(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	write(t, dir, "a_test.go", "package p\n\nfunc TestA(t *testing.T) {}\n\nfunc TestTwice(t *testing.T) {}\n")
	write(t, dir, "b_test.go", "package p\n\nfunc TestTwice(t *testing.T) {}\n\nfunc (s) TestMethod(t *testing.T) {}\n")
	write(t, dir, "c_test.go", "package p\n\nfunc TestBroken(\n")
	write(t, dir, "d.go", "package p\n\nfunc TestNotATestFile() {}\n")
	got := (&Project{work: root}).testFuncs(dir)
	want := map[string]string{"TestA": "p/a_test.go", "TestTwice": "p/a_test.go"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	if got := (&Project{work: root}).testFuncs(filepath.Join(root, "gone")); len(got) != 0 {
		t.Fatalf("a missing directory has no tests, got %v", got)
	}
}

func TestManyTestsAcrossManyFilesAreEachFoundInTheirFile(t *testing.T) {
	root := t.TempDir()
	const files, perFile = 200, 50
	for f := 0; f < files; f++ {
		var b strings.Builder
		b.WriteString("package p\n\nimport \"testing\"\n")
		for n := 0; n < perFile; n++ {
			fmt.Fprintf(&b, "\nfunc Test%d_%d(t *testing.T) {\n\tif %d > %d {\n\t\tt.Fatal()\n\t}\n}\n", f, n, f, n)
		}
		write(t, root, fmt.Sprintf("f%03d_test.go", f), b.String())
	}
	got := (&Project{work: root}).testFuncs(root)
	if len(got) != files*perFile {
		t.Fatalf("found %d tests, want %d", len(got), files*perFile)
	}
	for f := 0; f < files; f++ {
		for n := 0; n < perFile; n++ {
			name, want := fmt.Sprintf("Test%d_%d", f, n), fmt.Sprintf("f%03d_test.go", f)
			if got[name] != want {
				t.Fatalf("%s is in %q, want %q", name, got[name], want)
			}
		}
	}
}

// What a scan of every region of the file credits a block's line to.
func scanned(rs []*region, line int) []*region {
	var out []*region
	for _, r := range rs {
		if r.function && r.from <= line && line <= r.to {
			out = append(out, r)
		}
	}
	return out
}

func indexed(lines [][]*region, line int) []*region {
	if line < 0 || line >= len(lines) {
		return nil
	}
	return lines[line]
}

func TestABlockIsCreditedToEveryFunctionItsLineFallsIn(t *testing.T) {
	a := &region{file: "f.go", from: 3, to: 5, function: true}
	b := &region{file: "f.go", from: 5, to: 9, function: true}
	c := &region{file: "f.go", from: 5, to: 5, function: true}
	v := &region{file: "f.go", from: 1, to: 2}
	index := byLine(map[string][]*region{"f.go": {v, a, b, c}})
	for line, want := range map[int][]*region{0: nil, 1: nil, 3: {a}, 5: {a, b, c}, 9: {b}, 10: nil} {
		if got := indexed(index["f.go"], line); !reflect.DeepEqual(got, want) {
			t.Fatalf("line %d: got %v, want %v", line, got, want)
		}
	}
	if got := indexed(index["g.go"], 3); got != nil {
		t.Fatalf("a file without regions credits nothing, got %v", got)
	}
}

func TestAFileOfManyFunctionsIsCreditedAsAScanWould(t *testing.T) {
	var rs []*region
	for i := 0; i < 5000; i++ {
		from := 1 + 4*i
		rs = append(rs, &region{file: "f.go", from: from, to: from + 3 + i%2, function: i%7 != 0})
	}
	lines := byLine(map[string][]*region{"f.go": rs})["f.go"]
	for line := 0; line <= 4*5000+5; line++ {
		if got, want := indexed(lines, line), scanned(rs, line); !reflect.DeepEqual(got, want) {
			t.Fatalf("line %d: got %v, want %v", line, got, want)
		}
	}
}
