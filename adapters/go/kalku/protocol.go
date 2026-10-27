package kalku

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"unicode/utf8"
)

// Protocol is the version of the kalku protocol this kalku speaks.
const Protocol = 1

// MaxLine is the longest line either side may write, without its newline.
const MaxLine = 4 * 1024 * 1024

// The spells of the shared catalogue, by their wire names.
var allSpells = []string{"arm", "compare", "connect", "negate", "literal", "call", "return", "await", "supervise", "foreign"}

// Spells is what this kalku can cast. `await` and `supervise` are for
// runtimes with supervised processes, and `foreign` is a driver's.
var Spells = []string{"arm", "compare", "connect", "negate", "literal", "call", "return"}

// DecodeError is a line this kalku cannot accept, of one of the kinds the
// protocol names.
type DecodeError struct {
	Kind   string
	Detail string
	ID     *int64
}

func (e *DecodeError) Error() string { return e.Kind + ": " + e.Detail }

// Hello is the first request after summoning.
type Hello struct {
	Protocol         int64
	Root             string
	Reni             string
	Worker           int64
	InlineLimitBytes int64
	Env              map[string]string
	Options          map[string]any
}

// SitesRequest asks where wekufe can be cast in some files.
type SitesRequest struct {
	Files        []string
	Spells       []string
	ExcludeCalls []string
}

// CastRequest asks for one wekufe to be judged by some tests.
type CastRequest struct {
	Wekufe string
	Site   Site
	Tests  []string
	Rest   []string
}

// Request is one decoded request; exactly one of its fields is set, as
// `Type` says.
type Request struct {
	Type   string
	ID     int64
	Hello  *Hello
	Sites  *SitesRequest
	Cast   *CastRequest
	Abort  int64
	Reload []string
	// The tests a baseline may leave out, with the fingerprint each had when
	// it last ran.
	Reuse map[string]string
}

// Decode reads one line of the kalku protocol, as sent to a kalku.
func Decode(line string) (*Request, error) {
	d := json.NewDecoder(strings.NewReader(line))
	d.UseNumber()
	var v any
	if err := d.Decode(&v); err != nil {
		return nil, &DecodeError{Kind: "not_json", Detail: err.Error()}
	}
	if d.More() {
		return nil, &DecodeError{Kind: "not_json", Detail: "more than one value on the line"}
	}
	o, ok := v.(map[string]any)
	if !ok {
		return nil, &DecodeError{Kind: "not_object", Detail: "the line is not a JSON object"}
	}
	t, ok := o["type"].(string)
	if !ok {
		return nil, &DecodeError{Kind: "missing_type", Detail: "no string `type`"}
	}
	id, ok := integer(o["id"])
	if !ok {
		return nil, &DecodeError{Kind: "missing_id", Detail: "no integer `id`"}
	}
	r := &Request{Type: t, ID: id}
	f := fields{o: o}
	switch t {
	case "hello":
		h := &Hello{
			Protocol:         f.int("protocol"),
			Root:             f.str("root"),
			Reni:             f.str("reni"),
			Worker:           f.int("worker"),
			InlineLimitBytes: f.int("inline_limit_bytes"),
			Env:              f.strings("env"),
		}
		if raw, present := f.optional("options"); present {
			if obj, ok := raw.(map[string]any); ok {
				h.Options = obj
			} else {
				f.fail("options", "expected an object")
			}
		}
		r.Hello = h
	case "prepare", "reset", "shutdown":
	case "baseline":
		if _, present := f.optional("reuse"); present {
			r.Reuse = f.reuse("reuse")
		}
	case "sites":
		r.Sites = &SitesRequest{
			Files:        f.list("files"),
			Spells:       f.spells("spells"),
			ExcludeCalls: f.list("exclude_calls"),
		}
	case "cast":
		c := &CastRequest{Wekufe: f.str("wekufe"), Tests: f.list("tests")}
		if raw, present := f.optional("site"); present {
			c.Site = f.site("site", raw)
		} else {
			f.fail("site", "missing")
		}
		if _, present := f.optional("rest"); present {
			c.Rest = f.list("rest")
		}
		r.Cast = c
	case "abort":
		r.Abort = f.int("cast")
	case "reload":
		r.Reload = f.list("files")
	case "delegate":
		f.scope("scope")
		if _, present := f.optional("spells"); present {
			f.spells("spells")
		}
	default:
		return nil, &DecodeError{Kind: "unknown_type", Detail: t, ID: &id}
	}
	if f.err != "" {
		return nil, &DecodeError{Kind: "bad_field", Detail: f.err, ID: &id}
	}
	return r, nil
}

// An integer, and nothing that only looks like one.
func integer(v any) (int64, bool) {
	n, ok := v.(json.Number)
	if !ok {
		return 0, false
	}
	i, err := strconv.ParseInt(string(n), 10, 64)
	return i, err == nil
}

// The fields of one object, read with the first problem kept.
type fields struct {
	o   map[string]any
	err string
}

func (f *fields) fail(path, why string) {
	if f.err == "" {
		f.err = path + ": " + why
	}
}

// The first problem of an object inside this one, named by its path.
func (f *fields) nest(key string, inner fields) {
	if f.err == "" && inner.err != "" {
		f.err = key + "." + inner.err
	}
}

// A field that is missing or null is absent.
func (f *fields) optional(key string) (any, bool) {
	v, ok := f.o[key]
	return v, ok && v != nil
}

func (f *fields) required(key string) any {
	v, ok := f.optional(key)
	if !ok {
		f.fail(key, "missing")
	}
	return v
}

func (f *fields) str(key string) string {
	v := f.required(key)
	s, ok := v.(string)
	if v != nil && !ok {
		f.fail(key, "expected a string")
	}
	return s
}

func (f *fields) int(key string) int64 {
	v := f.required(key)
	i, ok := integer(v)
	if v != nil && !ok {
		f.fail(key, "expected an integer")
	}
	return i
}

func (f *fields) list(key string) []string {
	v := f.required(key)
	if v == nil {
		return nil
	}
	items, ok := v.([]any)
	if !ok {
		f.fail(key, "expected a list")
		return nil
	}
	out := make([]string, 0, len(items))
	for i, item := range items {
		s, ok := item.(string)
		if !ok {
			f.fail(fmt.Sprintf("%s[%d]", key, i), "expected a string")
		}
		out = append(out, s)
	}
	return out
}

func (f *fields) reuse(key string) map[string]string {
	items, ok := f.required(key).([]any)
	if !ok {
		f.fail(key, "expected a list")
		return nil
	}
	out := make(map[string]string, len(items))
	for i, item := range items {
		o, ok := item.(map[string]any)
		if !ok {
			f.fail(fmt.Sprintf("%s[%d]", key, i), "expected an object")
			continue
		}
		in := fields{o: o}
		out[in.str("test")] = in.str("fingerprint")
		f.nest(fmt.Sprintf("%s[%d]", key, i), in)
	}
	return out
}

func (f *fields) strings(key string) map[string]string {
	v := f.required(key)
	if v == nil {
		return nil
	}
	o, ok := v.(map[string]any)
	if !ok {
		f.fail(key, "expected an object")
		return nil
	}
	out := make(map[string]string, len(o))
	for k, item := range o {
		s, ok := item.(string)
		if !ok {
			f.fail(key+"."+k, "expected a string")
		}
		out[k] = s
	}
	return out
}

func (f *fields) spells(key string) []string {
	names := f.list(key)
	for i, n := range names {
		if !isSpell(n) {
			f.fail(fmt.Sprintf("%s[%d]", key, i), "unknown spell "+strconv.Quote(n))
		}
	}
	return names
}

func isSpell(name string) bool {
	for _, s := range allSpells {
		if s == name {
			return true
		}
	}
	return false
}

func (f *fields) scope(key string) {
	v := f.required(key)
	if v == nil {
		return
	}
	o, ok := v.(map[string]any)
	if !ok {
		f.fail(key, "expected an object")
		return
	}
	if len(o) != 1 {
		f.fail(key, "expected exactly one of all, since, files")
		return
	}
	inner := fields{o: o}
	switch {
	case o["all"] != nil:
		if o["all"] != true {
			inner.fail("all", "expected true")
		}
	case o["since"] != nil:
		inner.str("since")
	case o["files"] != nil:
		inner.list("files")
	default:
		f.fail(key, "expected one of all, since, files")
	}
	f.nest(key, inner)
}

func (f *fields) site(key string, raw any) Site {
	o, ok := raw.(map[string]any)
	if !ok {
		f.fail(key, "expected an object")
		return Site{}
	}
	in := fields{o: o}
	s := Site{
		SiteID:      in.str("site_id"),
		File:        in.str("file"),
		Spell:       in.str("spell"),
		Replacement: in.str("replacement"),
		Reload:      in.str("reload"),
	}
	if v, ok := in.optional("enclosing"); ok {
		s.Enclosing, ok = v.(string)
		if !ok {
			in.fail("enclosing", "expected a string")
		}
	}
	if v, ok := in.optional("ordinal"); ok {
		n, ok := integer(v)
		if !ok {
			in.fail("ordinal", "expected an integer")
		}
		s.Ordinal = int(n)
	}
	if v, ok := in.optional("original"); ok {
		text, ok := v.(string)
		if !ok {
			in.fail("original", "expected a string")
		}
		s.Original = &text
	}
	if in.err == "" && !isSpell(s.Spell) {
		in.fail("spell", "unknown spell "+strconv.Quote(s.Spell))
	}
	if in.err == "" && s.Reload != "module" && s.Reload != "dependents" {
		in.fail("reload", "expected module or dependents")
	}
	span, ok := in.required("span").(map[string]any)
	if in.err == "" && !ok {
		in.fail("span", "expected an object")
	}
	if span != nil {
		sp := fields{o: span}
		s.Start = sp.position("start")
		if _, ok := sp.optional("end"); ok {
			end := sp.position("end")
			s.End = &end
		}
		in.nest("span", sp)
	}
	f.nest(key, in)
	return s
}

func (f *fields) position(key string) Position {
	v := f.required(key)
	o, ok := v.(map[string]any)
	if v != nil && !ok {
		f.fail(key, "expected an object")
	}
	if o == nil {
		return Position{}
	}
	p := fields{o: o}
	pos := Position{Line: int(p.int("line")), Col: int(p.int("col")), Byte: int(p.int("byte"))}
	f.nest(key, p)
	return pos
}

// ---- writing ---------------------------------------------------------------

// A JSON object whose fields keep the order they were added in, which is
// the canonical order the protocol states.
type object struct{ b bytes.Buffer }

func message(kind string, id int64) *object {
	o := &object{}
	o.b.WriteString(`{"type":`)
	quote(&o.b, kind)
	o.b.WriteString(`,"id":`)
	o.b.WriteString(strconv.FormatInt(id, 10))
	return o
}

func newObject() *object {
	o := &object{}
	o.b.WriteByte('{')
	return o
}

func (o *object) key(k string) *object {
	if o.b.Len() > 1 {
		o.b.WriteByte(',')
	}
	quote(&o.b, k)
	o.b.WriteByte(':')
	return o
}

func (o *object) str(k, v string) *object {
	o.key(k)
	quote(&o.b, v)
	return o
}

func (o *object) int(k string, v int64) *object {
	o.key(k)
	o.b.WriteString(strconv.FormatInt(v, 10))
	return o
}

func (o *object) bool(k string, v bool) *object {
	o.key(k)
	o.b.WriteString(strconv.FormatBool(v))
	return o
}

func (o *object) strs(k string, v []string) *object {
	o.key(k)
	o.b.WriteByte('[')
	for i, s := range v {
		if i > 0 {
			o.b.WriteByte(',')
		}
		quote(&o.b, s)
	}
	o.b.WriteByte(']')
	return o
}

func (o *object) raw(k, json string) *object {
	o.key(k)
	o.b.WriteString(json)
	return o
}

func (o *object) objects(k string, items []string) *object {
	o.key(k)
	o.b.WriteByte('[')
	o.b.WriteString(strings.Join(items, ","))
	o.b.WriteByte(']')
	return o
}

func (o *object) String() string { return o.b.String() + "}" }

// A string in raw UTF-8, with only `"`, `\` and control characters escaped.
func quote(b *bytes.Buffer, s string) {
	b.WriteByte('"')
	for i := 0; i < len(s); {
		r, size := utf8.DecodeRuneInString(s[i:])
		switch {
		case r == '"':
			b.WriteString(`\"`)
		case r == '\\':
			b.WriteString(`\\`)
		case r == '\n':
			b.WriteString(`\n`)
		case r == '\r':
			b.WriteString(`\r`)
		case r == '\t':
			b.WriteString(`\t`)
		case r == '\b':
			b.WriteString(`\b`)
		case r == '\f':
			b.WriteString(`\f`)
		case r < 0x20:
			fmt.Fprintf(b, `\u%04x`, r)
		case r == utf8.RuneError && size == 1:
			b.WriteString(`�`)
		default:
			b.WriteString(s[i : i+size])
		}
		i += size
	}
	b.WriteByte('"')
}

// Ready answers `hello`.
func Ready(id int64, adapter, runtime string, capabilities []string) string {
	return message("ready", id).
		int("protocol", Protocol).
		str("language", "go").
		str("adapter", adapter).
		str("runtime", runtime).
		strs("spells", Spells).
		strs("capabilities", capabilities).String()
}

// Error answers any request that could not be served.
func Error(id int64, code, msg string, fatal bool) string {
	return message("error", id).str("code", code).str("message", msg).bool("fatal", fatal).String()
}

// Bye answers `shutdown`.
func Bye(id int64) string { return message("bye", id).String() }

// Prepared answers `prepare`.
func Prepared(id int64, durationMS int64, modules int) string {
	return message("prepared", id).int("duration_ms", durationMS).int("modules", int64(modules)).String()
}

// Timed is one test of a baseline and what it took.
type Timed struct {
	Test        string
	File        string
	DurationMS  int64
	Fingerprint string // what the test runs; empty when the kalku cannot say
}

// Failure is a test that failed in a baseline.
type Failure struct {
	Test    string
	Message string
}

// Coverage is what a baseline says about which tests reach which lines: at
// most one of its fields is set.
type Coverage struct {
	Inline     []Entry
	PackedPath string
	Withheld   string
}

// Entry is one line and the tests that reach it.
type Entry struct {
	File  string
	Line  int
	Tests []string
}

func (e Entry) json() string {
	return newObject().str("file", e.File).int("line", int64(e.Line)).strs("tests", e.Tests).String()
}

// BaselineDone answers `baseline`.
func BaselineDone(id int64, durationMS int64, tests []Timed, coverage Coverage, failures []Failure, differences, reused []string) string {
	status := "green"
	if len(failures) > 0 {
		status = "red"
	}
	ts := make([]string, len(tests))
	for i, t := range tests {
		obj := newObject().str("test", t.Test).str("file", t.File).int("duration_ms", t.DurationMS)
		if t.Fingerprint != "" {
			obj.str("fingerprint", t.Fingerprint)
		}
		ts[i] = obj.String()
	}
	fs := make([]string, len(failures))
	for i, f := range failures {
		fs[i] = newObject().str("test", f.Test).str("message", f.Message).String()
	}
	o := message("baseline_done", id).str("status", status).int("duration_ms", durationMS).objects("tests", ts)
	switch {
	case coverage.Inline != nil:
		es := make([]string, len(coverage.Inline))
		for i, e := range coverage.Inline {
			es[i] = e.json()
		}
		o.objects("coverage", es)
	case coverage.PackedPath != "":
		o.str("coverage_packed_path", coverage.PackedPath)
	case coverage.Withheld != "":
		o.str("coverage_withheld", coverage.Withheld)
	}
	o.objects("failures", fs)
	if len(differences) > 0 {
		o.strs("differences", differences)
	}
	if len(reused) > 0 {
		o.strs("reused", reused)
	}
	return o.String()
}

// Skipped is a file that yielded no sites, and why.
type Skipped struct {
	File    string
	Reason  string
	Message string
}

// SitesFound answers `sites`.
func SitesFound(id int64, sites []Site, skipped []Skipped) string {
	ss := make([]string, len(sites))
	for i, s := range sites {
		ss[i] = s.JSON()
	}
	ks := make([]string, len(skipped))
	for i, k := range skipped {
		ks[i] = newObject().str("file", k.File).str("reason", k.Reason).str("message", k.Message).String()
	}
	return message("sites_found", id).objects("sites", ss).objects("skipped", ks).String()
}

// Outcome is how a cast ended, as `cast_done` says it.
type Outcome struct {
	Kind     string
	KilledBy string
	Message  string
}

// CastDone answers `cast`.
func CastDone(id int64, wekufe string, o Outcome, durationMS int64) string {
	m := message("cast_done", id).str("wekufe", wekufe).str("outcome", o.Kind)
	if o.KilledBy != "" {
		m.str("killed_by", o.KilledBy)
	}
	if o.Message != "" {
		m.str("message", o.Message)
	}
	return m.int("duration_ms", durationMS).bool("dirty", false).String()
}

// Aborted answers `abort`.
func Aborted(id, cast int64, restored bool) string {
	return message("aborted", id).int("cast", cast).bool("restored", restored).String()
}

// ResetDone answers `reset`.
func ResetDone(id int64, clean bool, durationMS int64) string {
	return message("reset_done", id).bool("clean", clean).int("duration_ms", durationMS).String()
}

// Reloaded answers `reload`.
func Reloaded(id int64, modules []string, durationMS int64) string {
	return message("reloaded", id).strs("modules", modules).strs("dependents", []string{}).
		int("duration_ms", durationMS).String()
}

// Position is a place in a file: 1-based line, 1-based column in
// characters, 0-based byte offset.
type Position struct {
	Line int
	Col  int
	Byte int
}

func (p Position) json() string {
	return newObject().int("line", int64(p.Line)).int("col", int64(p.Col)).int("byte", int64(p.Byte)).String()
}

// Site is a place a wekufe can be cast.
type Site struct {
	SiteID      string
	File        string
	Enclosing   string
	Ordinal     int
	Start       Position
	End         *Position
	Spell       string
	Original    *string
	Replacement string
	Reload      string
}

// JSON is the site in canonical form.
func (s Site) JSON() string {
	o := newObject().str("site_id", s.SiteID).str("file", s.File)
	if s.Enclosing != "" {
		o.str("enclosing", s.Enclosing)
	}
	if s.Ordinal != 0 {
		o.int("ordinal", int64(s.Ordinal))
	}
	span := newObject().raw("start", s.Start.json())
	if s.End != nil {
		span.raw("end", s.End.json())
	}
	o.raw("span", span.String()).str("spell", s.Spell)
	if s.Original != nil {
		o.str("original", *s.Original)
	}
	return o.str("replacement", s.Replacement).str("reload", s.Reload).String()
}
