package enclosing

// What `enclosing` says: a function, a method by its receiver as a method
// expression writes it, without type parameters, and a package variable by
// its name. A function literal belongs to the function it is written in.
type Stack[T any] struct{ items []T }

func (s *Stack[T]) Push(v T) bool { return len(s.items) < 8 }

func (s Stack[T]) Len() int { return len(s.items) + 0 }

type Plain struct{}

func (Plain) Ok() bool { return true }

var Ready = 1 > 0

func Outer() func() bool {
	return func() bool { return 2 > 1 }
}
