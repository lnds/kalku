package limits

import "testing"

func TestWithin(t *testing.T) {
	if !Within(5) || Within(11) || Within(-1) {
		t.Error("Within is wrong")
	}
}

func TestRoom(t *testing.T) {
	if Room(250) != 5 {
		t.Error("Room(250) is not 5")
	}
}
