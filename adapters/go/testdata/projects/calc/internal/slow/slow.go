// Package slow has a test that takes as long as it is told to, so that a
// cast can be aborted while it runs.
package slow

// Twice is n doubled.
func Twice(n int) int {
	return n * 2
}
