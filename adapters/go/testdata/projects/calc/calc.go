// Package calc is a fixture: a few functions, tests that kill most of
// their wekufe, and a survivor left on purpose.
package calc

import "example.com/calc/internal/limits"

// Clamp keeps n within the limits. No test asks about High itself, so
// `>` and `>=` cannot be told apart: a survivor.
func Clamp(n int) int {
	if n < limits.Low {
		return limits.Low
	}
	if n > limits.High {
		return limits.High
	}
	return n
}

// Sign is -1, 0 or 1.
func Sign(n int) int {
	switch {
	case n < 0:
		return -1
	case n > 0:
		return 1
	default:
		return 0
	}
}
