package results

import (
	"errors"
	"strings"
)

// A returned value gives way to its type's zero value, one value at a
// time, where the declared type says what that is.
type Point struct{ X, Y int }

type Celsius float64

type Pair[T any] struct{ A, B T }

type Names []string

func Sum(a, b int) int { return a + b }

func Greet(name string) string { return "hi " + name }

func Origin(x int) Point { return Point{X: x} }

func Warm(c Celsius) Celsius { return c + 1 }

func Tags() Names { return Names{"a"} }

func Lookup(m map[string]*Point, k string) (*Point, error) {
	p, ok := m[k]
	if !ok {
		return nil, errors.New("missing")
	}
	return p, nil
}

func Index(s []int, v int) (int, bool) {
	for i, x := range s {
		if x == v {
			return i, true
		}
	}
	return -1, false
}

// Already the zero value, an error, a boolean the literal spell flips, a
// bare return, a type parameter, a type from another package, and an
// array: none of them is proposed.
func Zero() (int, string, *Point, Point) { return 0, "", nil, Point{} }

func ZeroSpelled() (float64, string) { return 0.0, `` }

func Fail() error { return errors.New("no") }

func Named() (n int) {
	n = 3
	return
}

func First[T any](xs []T) T { return xs[0] }

func Builder() strings.Builder { return strings.Builder{} }

func Grid() [2]int { return [2]int{1, 2} }

func Generic() Pair[int] { return Pair[int]{1, 2} }

// A value whose removal would leave a variable unused is left alone, and a
// function literal's own results decide its zero values.
func Doubled(n int) func() int {
	d := n * 2
	return func() int { return d + 1 }
}

func Count(s string) int {
	parts := strings.Split(s, ",")
	return len(parts)
}
