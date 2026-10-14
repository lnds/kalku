package arm

// A clause goes, unless it is `default` or the last one: those are what a
// switch falls to.
func Kind(v any) string {
	switch v.(type) {
	case int:
		return "int"
	case string, []byte:
		return "text"
	default:
		return "other"
	}
}

func Name(n int) string {
	switch n {
	case 1:
		return "one"
	default:
		return "many"
	case 2:
		return "two"
	}
}

func Only(n int) string {
	switch n {
	case 1:
		return "one"
	}
	return ""
}
