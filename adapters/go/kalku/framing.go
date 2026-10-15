package kalku

import (
	"bufio"
	"io"
)

// Line is one line read from the channel: its text, or the news that it
// was longer than the protocol allows.
type Line struct {
	Text    string
	TooLong bool
}

// ReadLine reads up to the next newline. A line longer than max is
// discarded as it is read, never held whole, so one oversized message
// costs only itself. It answers io.EOF when the input has ended.
func ReadLine(r *bufio.Reader, max int) (Line, error) {
	var buf []byte
	tooLong := false
	for {
		chunk, err := r.ReadSlice('\n')
		if !tooLong {
			buf = append(buf, chunk...)
			if len(buf) > max+1 || (len(buf) == max+1 && buf[max] != '\n') {
				tooLong, buf = true, nil
			}
		}
		switch err {
		case nil:
			if tooLong {
				return Line{TooLong: true}, nil
			}
			return Line{Text: string(buf[:len(buf)-1])}, nil
		case bufio.ErrBufferFull:
			continue
		case io.EOF:
			if tooLong {
				return Line{TooLong: true}, nil
			}
			if len(buf) == 0 {
				return Line{}, io.EOF
			}
			return Line{Text: string(buf)}, nil
		default:
			return Line{}, err
		}
	}
}
