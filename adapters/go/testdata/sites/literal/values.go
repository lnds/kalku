package literal

// Decimal integers get their successor and booleans the other value. An
// array's length, a key of a composite literal and a `case` value would
// change a type or repeat a case, so they are left alone; so are integers
// written for their digits, and a `true` that is somebody's variable.
const Limit = 10

var table = [4]int{1, 2, 3, 4}

var spread = []int{0: 7, 2: 9}

func Pick(n int) (int, bool) {
	switch n {
	case 1, 2:
		return 0x1F, true
	case 3:
		return 0755, false
	}
	return 1_000, n > 99
}

func Shadowed() bool {
	true := false
	return true
}

func Text() string {
	return "42 is not a literal here, and neither is true"
}

// The largest value of a sized type steps down: `256` is not a byte.
var hex = [3]byte{255, 0, 255}

func Big() (int16, uint64) {
	return 32767, 18446744073709551615
}
