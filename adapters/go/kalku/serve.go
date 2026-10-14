package kalku

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"os"
	"sync"
)

// Serve answers requests from in on out until `shutdown`.
//
// Requests are read on a goroutine of their own, so that an `abort` is
// heard while a cast is still running; everything else is served in order,
// here. That goroutine also hears the input end. After a `shutdown` it is a
// driver that said all it had to say, and every request before it is
// answered. With none it is a run that is gone, and the process ends there,
// with what it started.
func Serve(in io.Reader, out io.Writer, s *Service, gone func()) error {
	type heard struct {
		line Line
		req  *Request
		err  error
	}
	var (
		mu      sync.Mutex
		running int64 = -1
		cancel  context.CancelFunc
	)
	lines := make(chan heard, 64)
	go func() {
		r := bufio.NewReaderSize(in, 64*1024)
		for {
			line, err := ReadLine(r, MaxLine)
			if err == io.EOF {
				gone()
				return
			}
			if err != nil {
				fmt.Fprintln(os.Stderr, "kalku-go:", err)
				gone()
				return
			}
			h := heard{line: line}
			if !line.TooLong {
				h.req, h.err = Decode(line.Text)
			}
			if h.req != nil && h.req.Type == "abort" {
				mu.Lock()
				if running == h.req.Abort && cancel != nil {
					cancel()
				}
				mu.Unlock()
			}
			lines <- h
			if h.req != nil && h.req.Type == "shutdown" {
				close(lines)
				return
			}
		}
	}()
	w := bufio.NewWriter(out)
	for h := range lines {
		var reply Reply
		switch {
		case h.line.TooLong:
			reply = say(Error(0, "bad_request", fmt.Sprintf("line_too_long: a line longer than %d bytes", MaxLine), false))
		case h.err != nil:
			reply = Refuse(h.err)
		default:
			ctx, stop := context.WithCancel(context.Background())
			mu.Lock()
			running, cancel = h.req.ID, stop
			mu.Unlock()
			reply = s.Handle(ctx, h.req)
			mu.Lock()
			running, cancel = -1, nil
			mu.Unlock()
			stop()
		}
		if reply.Quiet {
			continue
		}
		if _, err := w.WriteString(reply.Line + "\n"); err != nil {
			return err
		}
		if err := w.Flush(); err != nil {
			return err
		}
		if reply.Last {
			return nil
		}
	}
	return nil
}
