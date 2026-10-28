#include "protocol.hpp"

namespace kalku {

const std::vector<std::string> SPELLS = {"arm", "compare", "connect", "negate", "literal", "call", "return"};

namespace {

const std::vector<std::string> ALL_SPELLS = {"arm",  "compare", "connect", "negate",    "literal",
                                             "call", "return",  "await",   "supervise", "foreign"};

// The fields of one object, read with the first problem kept.
class Fields {
 public:
  explicit Fields(const Json& o) : o_(o) {}

  std::string err;

  void fail(const std::string& path, const std::string& why) {
    if (err.empty()) err = path + ": " + why;
  }

  void nest(const std::string& key, const Fields& inner) {
    if (err.empty() && !inner.err.empty()) err = key + "." + inner.err;
  }

  // A field that is missing or null is absent.
  const Json* optional(const std::string& key) const {
    const Json* v = o_.get(key);
    return v && !v->is_null() ? v : nullptr;
  }

  const Json* required(const std::string& key) {
    const Json* v = optional(key);
    if (!v) fail(key, "missing");
    return v;
  }

  std::string str(const std::string& key) {
    const Json* v = required(key);
    if (!v) return "";
    if (!v->is_string()) {
      fail(key, "expected a string");
      return "";
    }
    return v->text;
  }

  std::int64_t integer(const std::string& key) {
    const Json* v = required(key);
    if (!v) return 0;
    if (!v->is_integer()) {
      fail(key, "expected an integer");
      return 0;
    }
    return v->integer;
  }

  std::vector<std::string> list(const std::string& key) {
    std::vector<std::string> out;
    const Json* v = required(key);
    if (!v) return out;
    if (!v->is_array()) {
      fail(key, "expected a list");
      return out;
    }
    for (std::size_t i = 0; i < v->items.size(); i++) {
      if (!v->items[i].is_string()) {
        fail(key + "[" + std::to_string(i) + "]", "expected a string");
        continue;
      }
      out.push_back(v->items[i].text);
    }
    return out;
  }

  std::vector<std::string> spells(const std::string& key) {
    auto names = list(key);
    for (std::size_t i = 0; i < names.size(); i++) {
      if (!is_spell(names[i])) fail(key + "[" + std::to_string(i) + "]", "unknown spell " + quote(names[i]));
    }
    return names;
  }

  std::map<std::string, std::string> strings(const std::string& key) {
    std::map<std::string, std::string> out;
    const Json* v = required(key);
    if (!v) return out;
    if (!v->is_object()) {
      fail(key, "expected an object");
      return out;
    }
    for (const auto& f : v->fields) {
      if (!f.second.is_string()) {
        fail(key + "." + f.first, "expected a string");
        continue;
      }
      out[f.first] = f.second.text;
    }
    return out;
  }

  std::map<std::string, std::string> reuse(const std::string& key) {
    std::map<std::string, std::string> out;
    const Json* v = required(key);
    if (!v) return out;
    if (!v->is_array()) {
      fail(key, "expected a list");
      return out;
    }
    for (std::size_t i = 0; i < v->items.size(); i++) {
      std::string at = key + "[" + std::to_string(i) + "]";
      if (!v->items[i].is_object()) {
        fail(at, "expected an object");
        continue;
      }
      Fields in(v->items[i]);
      std::string test = in.str("test");
      out[test] = in.str("fingerprint");
      nest(at, in);
    }
    return out;
  }

  Position position(const std::string& key) {
    Position p;
    const Json* v = required(key);
    if (!v) return p;
    if (!v->is_object()) {
      fail(key, "expected an object");
      return p;
    }
    Fields in(*v);
    p.line = static_cast<int>(in.integer("line"));
    p.col = static_cast<int>(in.integer("col"));
    p.byte = static_cast<long>(in.integer("byte"));
    nest(key, in);
    return p;
  }

  Site site(const std::string& key) {
    Site s;
    const Json* v = required(key);
    if (!v) return s;
    if (!v->is_object()) {
      fail(key, "expected an object");
      return s;
    }
    Fields in(*v);
    s.site_id = in.str("site_id");
    s.file = in.str("file");
    s.spell = in.str("spell");
    s.replacement = in.str("replacement");
    s.reload = in.str("reload");
    if (const Json* e = in.optional("enclosing")) {
      if (e->is_string()) {
        s.enclosing = e->text;
      } else {
        in.fail("enclosing", "expected a string");
      }
    }
    if (const Json* o = in.optional("ordinal")) {
      if (o->is_integer()) {
        s.ordinal = static_cast<int>(o->integer);
      } else {
        in.fail("ordinal", "expected an integer");
      }
    }
    if (const Json* o = in.optional("original")) {
      if (o->is_string()) {
        s.original = o->text;
      } else {
        in.fail("original", "expected a string");
      }
    }
    if (in.err.empty() && !is_spell(s.spell)) in.fail("spell", "unknown spell " + quote(s.spell));
    if (in.err.empty() && s.reload != "module" && s.reload != "dependents") {
      in.fail("reload", "expected module or dependents");
    }
    const Json* span = in.required("span");
    if (span && !span->is_object()) {
      in.fail("span", "expected an object");
    } else if (span) {
      Fields sp(*span);
      s.start = sp.position("start");
      if (sp.optional("end")) s.end = sp.position("end");
      in.nest("span", sp);
    }
    nest(key, in);
    return s;
  }

  void scope(const std::string& key) {
    const Json* v = required(key);
    if (!v) return;
    if (!v->is_object()) {
      fail(key, "expected an object");
      return;
    }
    if (v->fields.size() != 1) {
      fail(key, "expected exactly one of all, since, files");
      return;
    }
    Fields in(*v);
    if (const Json* all = in.optional("all")) {
      if (!all->is_bool() || !all->boolean) in.fail("all", "expected true");
    } else if (in.optional("since")) {
      in.str("since");
    } else if (in.optional("files")) {
      in.list("files");
    } else {
      fail(key, "expected one of all, since, files");
    }
    nest(key, in);
  }

 private:
  const Json& o_;
};

}  // namespace

bool is_spell(const std::string& name) {
  for (const auto& s : ALL_SPELLS) {
    if (s == name) return true;
  }
  return false;
}

std::optional<Request> decode(const std::string& line, DecodeError* error) {
  auto refuse = [&](const std::string& kind, const std::string& detail, std::optional<std::int64_t> id) {
    if (error) *error = DecodeError{kind, detail, id};
    return std::optional<Request>();
  };
  std::string why;
  auto parsed = parse_json(line, &why);
  if (!parsed) return refuse("not_json", why, std::nullopt);
  const Json& o = *parsed;
  if (!o.is_object()) return refuse("not_object", "the line is not a JSON object", std::nullopt);
  const Json* type = o.get("type");
  if (!type || !type->is_string()) return refuse("missing_type", "no string `type`", std::nullopt);
  const Json* idv = o.get("id");
  if (!idv || !idv->is_integer()) return refuse("missing_id", "no integer `id`", std::nullopt);
  Request r;
  r.type = type->text;
  r.id = idv->integer;
  Fields f(o);
  const std::string& t = r.type;
  if (t == "hello") {
    r.hello.protocol = f.integer("protocol");
    r.hello.root = f.str("root");
    r.hello.reni = f.str("reni");
    r.hello.worker = f.integer("worker");
    r.hello.inline_limit_bytes = f.integer("inline_limit_bytes");
    r.hello.env = f.strings("env");
    if (const Json* opts = f.optional("options")) {
      if (opts->is_object()) {
        r.hello.options = *opts;
      } else {
        f.fail("options", "expected an object");
      }
    }
  } else if (t == "prepare" || t == "reset" || t == "shutdown") {
  } else if (t == "baseline") {
    if (f.optional("reuse")) r.reuse = f.reuse("reuse");
  } else if (t == "sites") {
    r.sites.files = f.list("files");
    r.sites.spells = f.spells("spells");
    r.sites.exclude_calls = f.list("exclude_calls");
  } else if (t == "cast") {
    r.cast.wekufe = f.str("wekufe");
    r.cast.site = f.site("site");
    r.cast.tests = f.list("tests");
    if (f.optional("rest")) r.cast.rest = f.list("rest");
  } else if (t == "abort") {
    r.abort = f.integer("cast");
  } else if (t == "reload") {
    r.reload = f.list("files");
  } else if (t == "delegate") {
    f.scope("scope");
    if (f.optional("spells")) f.spells("spells");
  } else {
    return refuse("unknown_type", t, r.id);
  }
  if (!f.err.empty()) return refuse("bad_field", f.err, r.id);
  return r;
}

// ---- writing ---------------------------------------------------------------

namespace {

std::string position_json(const Position& p) {
  return Writer().integer("line", p.line).integer("col", p.col).integer("byte", p.byte).done();
}

}  // namespace

std::string Site::json() const {
  Writer w;
  w.str("site_id", site_id).str("file", file);
  if (!enclosing.empty()) w.str("enclosing", enclosing);
  if (ordinal != 0) w.integer("ordinal", ordinal);
  Writer span;
  span.raw("start", position_json(start));
  if (end) span.raw("end", position_json(*end));
  w.raw("span", span.done()).str("spell", spell);
  if (original) w.str("original", *original);
  return w.str("replacement", replacement).str("reload", reload).done();
}

std::string ready(std::int64_t id, const std::string& adapter, const std::string& runtime,
                  const std::vector<std::string>& capabilities) {
  return Writer::message("ready", id)
      .integer("protocol", PROTOCOL)
      .str("language", "cpp")
      .str("adapter", adapter)
      .str("runtime", runtime)
      .strs("spells", SPELLS)
      .strs("capabilities", capabilities)
      .done();
}

std::string error(std::int64_t id, const std::string& code, const std::string& message, bool fatal) {
  return Writer::message("error", id).str("code", code).str("message", message).boolean("fatal", fatal).done();
}

std::string bye(std::int64_t id) { return Writer::message("bye", id).done(); }

std::string prepared(std::int64_t id, std::int64_t duration_ms, std::int64_t modules) {
  return Writer::message("prepared", id).integer("duration_ms", duration_ms).integer("modules", modules).done();
}

std::string baseline_done(std::int64_t id, std::int64_t duration_ms, const std::vector<Timed>& tests,
                          const std::vector<Failure>& failures, const std::vector<std::string>& differences,
                          const Covered& covered, const std::vector<std::string>& reused) {
  std::vector<std::string> ts;
  for (const auto& t : tests) {
    Writer w;
    w.str("test", t.test).str("file", t.file).integer("duration_ms", t.duration_ms);
    if (!t.fingerprint.empty()) w.str("fingerprint", t.fingerprint);
    ts.push_back(w.done());
  }
  std::vector<std::string> fs;
  for (const auto& f : failures) fs.push_back(Writer().str("test", f.test).str("message", f.message).done());
  Writer w = Writer::message("baseline_done", id);
  w.str("status", failures.empty() ? "green" : "red").integer("duration_ms", duration_ms).objects("tests", ts);
  if (!covered.entries.empty()) w.raw("coverage", covered.entries);
  if (!covered.packed_path.empty()) w.str("coverage_packed_path", covered.packed_path);
  if (!covered.withheld.empty()) w.str("coverage_withheld", covered.withheld);
  w.objects("failures", fs);
  if (!differences.empty()) w.strs("differences", differences);
  if (!reused.empty()) w.strs("reused", reused);
  return w.done();
}

std::string sites_found(std::int64_t id, const std::vector<Site>& sites, const std::vector<Skipped>& skipped) {
  std::vector<std::string> ss;
  for (const auto& s : sites) ss.push_back(s.json());
  std::vector<std::string> ks;
  for (const auto& k : skipped) {
    Writer w;
    w.str("file", k.file).str("reason", k.reason).str("message", k.message);
    if (k.by_design) w.boolean("by_design", true);
    ks.push_back(w.done());
  }
  return Writer::message("sites_found", id).objects("sites", ss).objects("skipped", ks).done();
}

std::string cast_done(std::int64_t id, const std::string& wekufe, const Outcome& o, std::int64_t duration_ms) {
  Writer w = Writer::message("cast_done", id);
  w.str("wekufe", wekufe).str("outcome", o.kind);
  if (!o.killed_by.empty()) w.str("killed_by", o.killed_by);
  if (!o.message.empty()) w.str("message", o.message);
  return w.integer("duration_ms", duration_ms).boolean("dirty", false).done();
}

std::string aborted(std::int64_t id, std::int64_t cast, bool restored) {
  return Writer::message("aborted", id).integer("cast", cast).boolean("restored", restored).done();
}

std::string reset_done(std::int64_t id, bool clean, std::int64_t duration_ms) {
  return Writer::message("reset_done", id).boolean("clean", clean).integer("duration_ms", duration_ms).done();
}

std::string reloaded(std::int64_t id, const std::vector<std::string>& modules, std::int64_t duration_ms) {
  return Writer::message("reloaded", id)
      .strs("modules", modules)
      .strs("dependents", {})
      .integer("duration_ms", duration_ms)
      .done();
}

}  // namespace kalku
