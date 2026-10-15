package calc

import "testing"

func TestClamp(t *testing.T) {
	for _, c := range []struct{ in, want int }{{-5, 0}, {5, 5}, {50, 10}} {
		if got := Clamp(c.in); got != c.want {
			t.Errorf("Clamp(%d) = %d, want %d", c.in, got, c.want)
		}
	}
}

func TestSign(t *testing.T) {
	t.Run("negative", func(t *testing.T) {
		if Sign(-3) != -1 {
			t.Error("Sign(-3) is not -1")
		}
	})
	t.Run("positive", func(t *testing.T) {
		if Sign(3) != 1 {
			t.Error("Sign(3) is not 1")
		}
	})
}
