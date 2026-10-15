package negate

// An `if` condition and a `for` condition are negated whole, with an
// `if`'s statement left alone; a `for` with no condition, or a range, has
// nothing to negate.
func Count(xs []int) int {
	n := 0
	for i := 0; i < len(xs); i++ {
		if v := xs[i]; v > 0 {
			n++
		}
	}
	for n > 100 {
		n /= 2
	}
	for range xs {
	}
	for {
		break
	}
	return n
}
