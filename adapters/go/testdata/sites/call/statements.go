package call

import (
	"fmt"
	"io"
	"log"
	"strings"
)

type counter struct{ n int }

func (c *counter) add() { c.n++ }

// A call that is a statement can go, unless it is the last use of an
// import or of a variable, which Go would refuse to leave unused, or a
// `panic` that ends its block, without which a function lacks a return.
// The logging the run excludes gets no sites at all.
func Work(c *counter, w io.Writer) int {
	c.add()
	fmt.Fprintln(w, "x")
	log.Printf("working on %d", 3)
	buf := new(strings.Builder)
	buf.WriteString("only use")
	kept := new(strings.Builder)
	kept.WriteString("one use")
	return kept.Len()
}

func Must(err error) bool {
	if err != nil {
		panic(err)
	}
	return fmt.Sprint(1) == "1" && true
}

func Fatal() int {
	panic("unreachable")
}
