package compare

// Every comparison has the neighbour that differs from it at one point:
// `>=` and `>`, `<` and `<=`, `==` and `!=`.
func Window(a, b int) bool {
	if a >= 0 && a < b {
		return a == b-1 || a != 3
	}
	// A comparison in a comment is not one: a >= b.
	return b <= a && b > 2 && "a < b" != ""
}
