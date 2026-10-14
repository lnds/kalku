package slow

import (
	"os"
	"testing"
	"time"
)

func TestTwice(t *testing.T) {
	if os.Getenv("CALC_SLOW") != "" {
		time.Sleep(time.Minute)
	}
	if Twice(2) != 4 {
		t.Error("Twice(2) is not 4")
	}
}
