// Package order holds a value one test sets and another reads, so that,
// when told to, a test passes in the suite and fails run alone.
package order

// Seen is set by whoever comes first.
var Seen int

// Mark sets Seen.
func Mark(n int) {
	Seen = n
}
