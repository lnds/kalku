package kalku

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The sites of every fixture, against a golden file beside it. A golden is
// only as good as its review: regenerate with `UPDATE_GOLDEN=1 go test
// ./...`, then read the diff.
func TestEveryFixtureMatchesItsGolden(t *testing.T) {
	fixtures, err := filepath.Glob("../testdata/sites/*/*.go")
	if err != nil || len(fixtures) < 7 {
		t.Fatalf("the fixtures went missing: %v %v", fixtures, err)
	}
	for _, path := range fixtures {
		text, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		name := strings.TrimPrefix(filepath.ToSlash(path), "../testdata/sites/")
		sites, err := FindSites(name, string(text), Spells, []string{"log.*"})
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		var b strings.Builder
		for _, s := range sites {
			b.WriteString(s.JSON() + "\n")
		}
		golden := strings.TrimSuffix(path, ".go") + ".sites.ndjson"
		if os.Getenv("UPDATE_GOLDEN") != "" {
			if err := os.WriteFile(golden, []byte(b.String()), 0o644); err != nil {
				t.Fatal(err)
			}
		}
		want, err := os.ReadFile(golden)
		if err != nil {
			t.Fatalf("no golden for %s", name)
		}
		if b.String() != string(want) {
			t.Errorf("%s: the sites are not the golden's\n got:\n%s\nwant:\n%s", name, b.String(), want)
		}
	}
}
