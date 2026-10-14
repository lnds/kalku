// Package limits holds the bounds calc clamps to.
package limits

const (
	Low  = 0
	High = 10
)

// Top is the largest byte.
const Top uint8 = 255

// Width is the size of a Cell. Its wekufe makes a Cell something that is
// not a [4]byte, which does not compile.
const Width = 4

// Cell is a few bytes.
type Cell [Width]byte

// Within says whether n is between the limits.
func Within(n int) bool {
	return n >= Low && n <= High
}

// Room is how far n is from Top.
func Room(n uint8) uint8 {
	return Top - n
}

// Blank is an empty Cell.
func Blank() [4]byte {
	return Cell{}
}
