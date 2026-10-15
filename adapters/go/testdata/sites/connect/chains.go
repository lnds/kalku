package connect

// `&&` and `||` swap; `&` and `|` are arithmetic, not connectives.
func Either(a, b, c bool, m, n int) bool {
	return a && (b || c) && m&n == 0 || m|n > 1
}
