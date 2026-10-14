// Command kalku-go is the Go kalku: it speaks the kalku protocol on stdio,
// finds sites with Go's own parser, and judges each wekufe with `go test`
// in a copy of the module kept in the reni.
package main

import (
	"fmt"
	"os"

	"github.com/lnds/kalku/adapters/go/kalku"
)

func main() {
	if len(os.Args) > 1 {
		fmt.Fprintf(os.Stderr, "kalku-go %s: summoned by kalku, which speaks to it on stdin; it takes no arguments\n", kalku.Version)
		os.Exit(2)
	}
	err := kalku.Serve(os.Stdin, os.Stdout, kalku.NewService(), func() { kalku.Leave(0) })
	if err != nil {
		fmt.Fprintln(os.Stderr, "kalku-go:", err)
		os.Exit(1)
	}
}
