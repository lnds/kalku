package kalku

import (
	"go/ast"
	"go/token"
)

// A `return` wekufe gives back the zero value of a result's type instead of
// what the function computed, one wekufe per value. The zero value is read
// from the declared result types, so it is proposed only where the type
// says what it is:
//
//   - a predeclared numeric type, `string`, `bool`, and pointers, slices,
//     maps, channels, functions and interfaces;
//   - a type declared in the same file, by what it is declared as: a
//     struct that is not generic is `T{}`.
//
// Left out: an `error` result, whose `nil` is another defect than a wrong
// value; a type from another file or package, an array, and a type
// parameter, whose zero value cannot be written without knowing more; a
// value that already is the zero value; a `true` or `false`, which the
// `literal` spell already flips; a bare `return` of named results; and a
// value whose removal would leave a variable or an import unused.

// Returns proposes the `return` sites of one function body, and not those
// of the function literals inside it, which walk proposes for each.
func (w *walker) returns(ft *ast.FuncType, body *ast.BlockStmt) {
	if !w.wanted["return"] || ft.Results == nil {
		return
	}
	var types []ast.Expr
	for _, f := range ft.Results.List {
		n := len(f.Names)
		if n == 0 {
			n = 1
		}
		for i := 0; i < n; i++ {
			types = append(types, f.Type)
		}
	}
	ast.Inspect(body, func(n ast.Node) bool {
		switch n := n.(type) {
		case *ast.FuncLit:
			return false
		case *ast.ReturnStmt:
			if len(n.Results) != len(types) {
				return true
			}
			for i, e := range n.Results {
				zero := w.zeroOf(types[i])
				if zero == "" || w.alreadyZero(e, zero) || isBool(e) || w.leavesUnused(e) {
					continue
				}
				s := w.spanOf(e)
				w.propose("return", s.from, s.to, zero)
			}
		}
		return true
	})
}

var predeclaredZero = map[string]string{
	"int": "0", "int8": "0", "int16": "0", "int32": "0", "int64": "0",
	"uint": "0", "uint8": "0", "uint16": "0", "uint32": "0", "uint64": "0", "uintptr": "0",
	"byte": "0", "rune": "0", "float32": "0", "float64": "0", "complex64": "0", "complex128": "0",
	"string": `""`, "bool": "false", "any": "nil",
}

// The zero value of a result's type as Go source, or "" where it cannot be
// written from what the file says.
func (w *walker) zeroOf(t ast.Expr) string {
	switch t := t.(type) {
	case *ast.StarExpr, *ast.MapType, *ast.ChanType, *ast.FuncType, *ast.InterfaceType:
		return "nil"
	case *ast.ArrayType:
		if t.Len == nil {
			return "nil"
		}
	case *ast.Ident:
		if t.Obj == nil {
			return predeclaredZero[t.Name]
		}
		return w.declaredZero(t)
	}
	return ""
}

// A type declared in this file, by what it is declared as.
func (w *walker) declaredZero(t *ast.Ident) string {
	spec, ok := t.Obj.Decl.(*ast.TypeSpec)
	if !ok || spec.TypeParams != nil || spec.Assign.IsValid() {
		return ""
	}
	if _, isStruct := spec.Type.(*ast.StructType); isStruct {
		return t.Name + "{}"
	}
	if id, ok := spec.Type.(*ast.Ident); ok && id.Obj != nil {
		// A type declared from another declared type; one step is enough
		// for the common case and keeps a cycle from looping.
		return ""
	}
	return w.zeroOf(spec.Type)
}

// The zero value as written, or a number or string spelled another way.
func (w *walker) alreadyZero(e ast.Expr, zero string) bool {
	s := w.spanOf(e)
	if w.text[s.from:s.to] == zero {
		return true
	}
	l, ok := e.(*ast.BasicLit)
	if !ok {
		return false
	}
	if l.Kind == token.STRING {
		return l.Value == "``"
	}
	return isZeroNumber(l.Value)
}

func isZeroNumber(v string) bool {
	for _, c := range v {
		if c != '0' && c != '.' && c != '_' {
			return false
		}
	}
	return true
}

func isBool(e ast.Expr) bool {
	id, ok := e.(*ast.Ident)
	return ok && id.Obj == nil && (id.Name == "true" || id.Name == "false")
}
