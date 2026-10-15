package order

import (
	"os"
	"testing"
)

func TestFirst(t *testing.T) {
	Mark(1)
}

func TestSecond(t *testing.T) {
	if os.Getenv("CALC_ORDER") != "" && Seen != 1 {
		t.Error("TestFirst did not run before")
	}
}
