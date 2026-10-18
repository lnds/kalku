#include "sites.hpp"

#include <algorithm>
#include <cstdio>
#include <map>
#include <set>

#include "sha256.hpp"
#include "source.hpp"

// What is never a site, and why:
//
//   - anything a macro wrote: its text is not where the wekufe would go;
//   - what is never evaluated: `sizeof`, `alignof`, `static_assert`, a
//     `requires`, a concept, an attribute, the condition of `if constexpr`;
//   - an enum's values and a template's arguments and defaults, where a
//     change changes a type rather than a value;
//   - a literal in a `case` label, which would repeat a case, and one that
//     sizes an array or a bit-field in a declaration;
//   - a call to an operator, a constructor or a destructor, a call that
//     ends a path (`exit`, `abort`, `std::terminate`), and anything in a
//     call `exclude_calls` names, that call included.

namespace kalku {

namespace {

struct Node {
  int kind = 0;
  long begin = -1;
  long end = -1;
  // Every character of it is written in this file, by hand.
  bool own = false;
  std::string spelling;
  // For a call: what it calls, qualified, and that declaration's kind.
  std::string callee;
  std::string callee_name;
  int callee_kind = 0;
  // For a declaration: its name, qualified by namespaces and classes.
  std::string qualified;
  std::vector<Node> children;
};

struct Tok {
  long begin;
  long end;
  int kind;
  std::string text;
};

bool is_expr(int k) { return k >= cx::FirstExpr && k <= cx::LastExpr; }
bool is_function(int k) {
  return k == cx::FunctionDecl || k == cx::CXXMethod || k == cx::Constructor || k == cx::Destructor ||
         k == cx::ConversionFunction || k == cx::FunctionTemplate;
}
bool is_scope(int k) {
  return k == cx::Namespace || k == cx::ClassDecl || k == cx::StructDecl || k == cx::UnionDecl ||
         k == cx::ClassTemplate || k == cx::ClassTemplatePartialSpecialization || k == cx::EnumDecl;
}

// ---- reading the parse -----------------------------------------------------

class Builder {
 public:
  Builder(const cx::Api& api, cx::TranslationUnit tu, cx::File main) : api_(api), tu_(tu), main_(main) {}

  Node root() {
    Node top;
    top.kind = cx::TranslationUnit_;
    Frame f{this, &top, true};
    api_.visitChildren(api_.getTranslationUnitCursor(tu_), &Builder::visit, &f);
    return top;
  }

  // Where this file invokes a macro: the text of each invocation.
  std::vector<std::pair<long, long>> macros;

 private:
  struct Frame {
    Builder* self;
    Node* into;
    bool top;
  };

  static cx::VisitResult visit(cx::Cursor c, cx::Cursor, cx::ClientData data) {
    auto* f = static_cast<Frame*>(data);
    Builder& b = *f->self;
    if (f->top && !b.api_.Location_isFromMainFile(b.api_.getCursorLocation(c))) return cx::Continue;
    if (b.api_.getCursorKind(c) == cx::MacroExpansion) {
      Node m = b.node(c);
      if (m.begin >= 0) b.macros.emplace_back(m.begin, m.end);
      return cx::Continue;
    }
    Node n = b.node(c);
    Frame inner{&b, &n, false};
    b.api_.visitChildren(c, &Builder::visit, &inner);
    f->into->children.push_back(std::move(n));
    return cx::Continue;
  }

  Node node(cx::Cursor c) {
    Node n;
    n.kind = api_.getCursorKind(c);
    cx::SourceRange r = api_.getCursorExtent(c);
    cx::File ef = nullptr, sf = nullptr, ef2 = nullptr, sf2 = nullptr;
    unsigned line, col, eo = 0, so = 0, eo2 = 0, so2 = 0;
    api_.getExpansionLocation(api_.getRangeStart(r), &ef, &line, &col, &eo);
    api_.getSpellingLocation(api_.getRangeStart(r), &sf, &line, &col, &so);
    api_.getExpansionLocation(api_.getRangeEnd(r), &ef2, &line, &col, &eo2);
    api_.getSpellingLocation(api_.getRangeEnd(r), &sf2, &line, &col, &so2);
    if (same(ef, main_) && same(ef2, main_)) {
      n.begin = eo;
      n.end = eo2;
    }
    n.own = n.begin >= 0 && same(sf, main_) && same(sf2, main_) && so == eo && so2 == eo2 && n.end > n.begin;
    n.spelling = cx::text(api_, api_.getCursorSpelling(c));
    if (n.kind == cx::CallExpr) {
      cx::Cursor callee = api_.getCursorReferenced(c);
      if (!api_.Cursor_isNull(callee)) {
        n.callee_kind = api_.getCursorKind(callee);
        n.callee_name = cx::text(api_, api_.getCursorSpelling(callee));
        n.callee = qualified(callee);
      }
    }
    if (is_function(n.kind) || n.kind == cx::VarDecl || n.kind == cx::FieldDecl) n.qualified = qualified(c);
    return n;
  }

  bool same(cx::File a, cx::File b) const {
    if (!a || !b) return false;
    if (a == b) return true;
    return false;
  }

  std::string qualified(cx::Cursor c) {
    std::string name = cx::text(api_, api_.getCursorSpelling(c));
    cx::Cursor p = api_.getCursorSemanticParent(c);
    while (!api_.Cursor_isNull(p)) {
      int k = api_.getCursorKind(p);
      if (k == cx::TranslationUnit_ || !is_scope(k)) break;
      std::string part = cx::text(api_, api_.getCursorSpelling(p));
      if (part.empty() || part.rfind("(anonymous", 0) == 0 || part.find("(unnamed") != std::string::npos) {
        part = "(anonymous)";
      }
      name = part + "::" + name;
      p = api_.getCursorSemanticParent(p);
    }
    return name;
  }

  const cx::Api& api_;
  cx::TranslationUnit tu_;
  cx::File main_;
};

// ---- finding sites ---------------------------------------------------------

struct Candidate {
  long from;
  long to;
  std::string spell;
  std::string replacement;
  std::string enclosing;
};

const std::map<std::string, std::string> COMPARE = {{"<", "<="}, {"<=", "<"},  {">", ">="},
                                                    {">=", ">"}, {"==", "!="}, {"!=", "=="}};
const std::map<std::string, std::string> CONNECT = {{"&&", "||"}, {"||", "&&"}};
const std::set<std::string> ENDS_A_PATH = {"abort",     "exit",        "_Exit",          "quick_exit",
                                           "terminate", "unreachable", "__builtin_trap", "__builtin_unreachable"};

class Walker {
 public:
  Walker(const std::string& text, std::vector<Tok> toks, std::vector<std::pair<long, long>> macros,
         const std::vector<std::string>& spells, const std::vector<std::string>& exclude)
      : text_(text), toks_(std::move(toks)), macros_(std::move(macros)), exclude_(exclude) {
    for (const auto& s : spells) wanted_.insert(s);
  }

  void walk(const Node& n) {
    const Node* parent = stack_.empty() ? nullptr : stack_.back();
    if (quiet(n, parent)) return;
    if (is_function(n.kind)) {
      if (!has_body(n)) return;
      std::string saved = enclosing_;
      enclosing_ = n.qualified + "(" + parameters(n) + ")";
      children(n);
      enclosing_ = saved;
      return;
    }
    if ((n.kind == cx::VarDecl || n.kind == cx::FieldDecl) && !in_function_) {
      std::string saved = enclosing_;
      enclosing_ = n.qualified;
      children(n);
      enclosing_ = saved;
      return;
    }
    if (n.own && !written(n.begin, n.end)) propose(n, effective(n));
    children(n);
  }

  std::vector<Candidate> found;

 private:
  void children(const Node& n) {
    bool was = in_function_;
    if (is_function(n.kind)) in_function_ = true;
    stack_.push_back(&n);
    if (n.kind == cx::CaseStmt) {
      // The label is a constant the switch is keyed by, not a value.
      for (std::size_t i = 1; i < n.children.size(); i++) walk(n.children[i]);
    } else if (n.kind == cx::IfStmt && constexpr_if(n)) {
      long past = header(n).second;
      for (const auto& c : n.children) {
        if (c.begin >= past) walk(c);
      }
    } else {
      for (const auto& c : n.children) walk(c);
    }
    stack_.pop_back();
    in_function_ = was;
  }

  // The parent a node is seen from: what the parse wraps it in without
  // writing anything (an implicit conversion, the end of a temporary's
  // life) is looked through.
  const Node* effective(const Node& n) const {
    for (std::size_t i = stack_.size(); i-- > 0;) {
      const Node* p = stack_[i];
      if (p->kind == cx::FirstExpr && p->begin == n.begin && p->end == n.end) continue;
      return p;
    }
    return nullptr;
  }

  // Subtrees that hold no site.
  bool quiet(const Node& n, const Node* parent) {
    switch (n.kind) {
      case cx::UnaryExpr:
      case cx::StaticAssert:
      case cx::RequiresExpr:
      case cx::ConceptSpecializationExpr:
      case cx::ConceptDecl:
      case cx::NonTypeTemplateParameter:
      case cx::EnumDecl:
        return true;
      default:
        break;
    }
    if (n.kind >= cx::FirstAttr && n.kind <= cx::LastAttr) return true;
    if (n.kind >= cx::FirstRef && n.kind <= cx::LastRef) return true;
    // A template's arguments, written after a name it refers to.
    if (parent && (parent->kind == cx::DeclRefExpr || parent->kind == cx::MemberRefExpr) && is_expr(n.kind)) {
      return true;
    }
    if (n.kind == cx::CallExpr && excluded(n)) return true;
    return false;
  }

  bool excluded(const Node& call) const {
    if (call.callee.empty()) return false;
    for (const auto& p : exclude_) {
      if (p == call.callee || p == call.callee_name) return true;
      for (const char* tail : {".*", "::*"}) {
        std::string t = tail;
        if (p.size() > t.size() && p.compare(p.size() - t.size(), t.size(), t) == 0) {
          std::string prefix = p.substr(0, p.size() - t.size()) + "::";
          if (call.callee.rfind(prefix, 0) == 0) return true;
        }
      }
    }
    return false;
  }

  static bool has_body(const Node& fn) {
    for (const auto& c : fn.children) {
      if (c.kind == cx::CompoundStmt) return true;
    }
    return false;
  }

  void add(const std::string& spell, long from, long to, const std::string& replacement) {
    if (!wanted_.count(spell) || from < 0 || to <= from || written(from, to) || crosses(from, to)) return;
    found.push_back(Candidate{from, to, spell, replacement, enclosing_});
  }

  // Text inside one macro invocation: a macro wrote it, or it is handed to
  // one, and in either case it is not where the wekufe would go.
  bool written(long from, long to) const {
    for (const auto& m : macros_) {
      if (m.first <= from && to <= m.second) return true;
    }
    return false;
  }

  // A span that begins or ends inside a macro invocation it does not hold.
  bool crosses(long from, long to) const {
    for (const auto& m : macros_) {
      bool overlaps = m.first < to && from < m.second;
      bool holds = from <= m.first && m.second <= to;
      if (overlaps && !holds) return true;
    }
    return false;
  }

  std::string slice(long from, long to) const {
    return text_.substr(static_cast<std::size_t>(from), static_cast<std::size_t>(to - from));
  }

  // The index of the first token at or after an offset.
  std::size_t token_at(long offset) const {
    auto it = std::lower_bound(toks_.begin(), toks_.end(), offset, [](const Tok& t, long o) { return t.begin < o; });
    return static_cast<std::size_t>(it - toks_.begin());
  }

  const Tok* token(long offset) const {
    std::size_t i = token_at(offset);
    return i < toks_.size() ? &toks_[i] : nullptr;
  }

  // The token before the one at an offset.
  const Tok* before(long offset) const {
    std::size_t i = token_at(offset);
    return i > 0 && i <= toks_.size() ? &toks_[i - 1] : nullptr;
  }

  // The parentheses after a statement's keyword: the offsets of `(` and of
  // the `)` that closes it.
  std::pair<long, long> header(const Node& n, long from = -1) const {
    std::size_t i = token_at(from < 0 ? n.begin : from);
    while (i < toks_.size() && toks_[i].begin < n.end && toks_[i].text != "(") i++;
    if (i >= toks_.size()) return {-1, -1};
    long open = toks_[i].begin;
    int depth = 0;
    for (; i < toks_.size() && toks_[i].begin < n.end; i++) {
      if (toks_[i].text == "(") depth++;
      if (toks_[i].text == ")" && --depth == 0) return {open, toks_[i].end};
    }
    return {-1, -1};
  }

  // The `;` at the top level of a header, in order.
  std::vector<long> semicolons(std::pair<long, long> h) const {
    std::vector<long> out;
    int depth = 0;
    for (std::size_t i = token_at(h.first); i < toks_.size() && toks_[i].begin < h.second; i++) {
      const std::string& t = toks_[i].text;
      if (t == "(" || t == "[" || t == "{") depth++;
      if (t == ")" || t == "]" || t == "}") depth--;
      if (t == ";" && depth == 1) out.push_back(toks_[i].begin);
    }
    return out;
  }

  bool constexpr_if(const Node& n) const {
    std::size_t i = token_at(n.begin);
    return i + 1 < toks_.size() && toks_[i].text == "if" &&
           (toks_[i + 1].text == "constexpr" || toks_[i + 1].text == "consteval" || toks_[i + 1].text == "!");
  }

  std::string parameters(const Node& fn) const {
    std::string out;
    bool first = true;
    for (const auto& c : fn.children) {
      if (c.kind != cx::ParmDecl || c.begin < 0) continue;
      long stop = c.end;
      for (std::size_t i = token_at(c.begin); i < toks_.size() && toks_[i].begin < c.end; i++) {
        if (toks_[i].text == "=") {
          stop = toks_[i].begin;
          break;
        }
      }
      long name = -1;
      for (std::size_t i = token_at(c.begin); i < toks_.size() && toks_[i].begin < stop; i++) {
        if (toks_[i].kind == cx::Identifier && toks_[i].text == c.spelling) name = toks_[i].begin;
      }
      std::string type = slice(c.begin, name >= 0 ? name : stop);
      out += (first ? "" : ",") + tidy(type);
      first = false;
    }
    return out;
  }

  static std::string tidy(const std::string& s) {
    std::string out;
    bool gap = false;
    for (char ch : s) {
      if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
        gap = !out.empty();
        continue;
      }
      if (gap) out += ' ';
      gap = false;
      out += ch;
    }
    return out;
  }

  void propose(const Node& n, const Node* parent) {
    switch (n.kind) {
      case cx::BinaryOperator:
        binary(n);
        break;
      case cx::UnaryOperator:
        if (const Tok* t = token(n.begin); t && t->begin == n.begin && t->text == "!")
          add("negate", t->begin, t->end, "");
        break;
      case cx::IfStmt:
        if (!constexpr_if(n)) condition(n, header(n));
        break;
      case cx::WhileStmt:
        condition(n, header(n));
        break;
      case cx::DoStmt:
        condition(n, header(n, last_while(n)));
        break;
      case cx::ForStmt:
        loop(n);
        break;
      case cx::ConditionalOperator:
        if (!n.children.empty() && n.children[0].own) negate(n.children[0]);
        break;
      case cx::IntegerLiteral:
      case cx::CXXBoolLiteralExpr:
      case cx::StringLiteral:
        literal(n, parent);
        break;
      case cx::SwitchStmt:
        arms(n);
        break;
      case cx::CallExpr:
        if (statement(n, parent)) call(n);
        break;
      default:
        break;
    }
  }

  void binary(const Node& n) {
    if (n.children.size() != 2) return;
    const Tok* op = token(n.children[0].end);
    if (!op || op->end > n.children[1].begin) return;
    auto c = COMPARE.find(op->text);
    if (c != COMPARE.end()) add("compare", op->begin, op->end, c->second);
    auto j = CONNECT.find(op->text);
    if (j != CONNECT.end()) add("connect", op->begin, op->end, j->second);
  }

  // A condition that is already `!x` is negated by dropping its `!`, once.
  void negate(const Node& c) {
    if (c.kind == cx::UnaryOperator) {
      const Tok* t = token(c.begin);
      if (t && t->begin == c.begin && t->text == "!") return;
    }
    add("negate", c.begin, c.end, "!(" + slice(c.begin, c.end) + ")");
  }

  // The condition of an `if` or a `while`: the last thing in its header,
  // after any init-statement, when it is an expression and not a variable
  // the condition declares.
  void condition(const Node& n, std::pair<long, long> h) {
    if (h.first < 0) return;
    auto semis = semicolons(h);
    long after = semis.empty() ? h.first : semis.back();
    const Node* last = nullptr;
    for (const auto& c : n.children) {
      if (c.begin <= h.first || c.end > h.second) continue;
      if (c.begin >= after && (c.kind == cx::VarDecl || c.kind == cx::DeclStmt)) return;
      last = &c;
    }
    if (!last || last->begin < after || !is_expr(last->kind) || !last->own) return;
    negate(*last);
  }

  long last_while(const Node& n) const {
    long at = -1;
    for (std::size_t i = token_at(n.begin); i < toks_.size() && toks_[i].begin < n.end; i++) {
      if (toks_[i].text == "while") at = toks_[i].begin;
    }
    return at;
  }

  void loop(const Node& n) {
    auto h = header(n);
    if (h.first < 0) return;
    auto semis = semicolons(h);
    if (semis.size() != 2) return;
    for (const auto& c : n.children) {
      if (c.begin > semis[0] && c.end <= semis[1] && is_expr(c.kind) && c.own) negate(c);
    }
  }

  void literal(const Node& n, const Node* parent) {
    if (!literal_allowed(n, parent)) return;
    std::size_t i = token_at(n.begin);
    if (i >= toks_.size() || toks_[i].begin != n.begin || toks_[i].end != n.end) return;
    const std::string& t = toks_[i].text;
    if (n.kind == cx::CXXBoolLiteralExpr) {
      if (t == "true") add("literal", n.begin, n.end, "false");
      if (t == "false") add("literal", n.begin, n.end, "true");
      return;
    }
    if (n.kind == cx::StringLiteral) {
      if (t.size() > 2 && t.front() == '"' && t.back() == '"') add("literal", n.begin, n.end, "\"\"");
      return;
    }
    std::size_t digits = 0;
    while (digits < t.size() && t[digits] >= '0' && t[digits] <= '9') digits++;
    if (digits == 0 || (digits > 1 && t[0] == '0')) return;
    for (std::size_t k = digits; k < t.size(); k++) {
      char ch = t[k];
      if (ch != 'u' && ch != 'U' && ch != 'l' && ch != 'L' && ch != 'z' && ch != 'Z') return;
    }
    add("literal", n.begin, n.end, increment(t.substr(0, digits)) + t.substr(digits));
  }

  static std::string increment(std::string d) {
    for (std::size_t k = d.size(); k-- > 0;) {
      if (d[k] != '9') {
        d[k]++;
        return d;
      }
      d[k] = '0';
    }
    return "1" + d;
  }

  // A literal that is a value: in an expression, or what a declaration is
  // initialised with, and not the size of an array or a bit-field.
  bool literal_allowed(const Node& n, const Node* parent) const {
    if (!parent) return false;
    int k = parent->kind;
    if (k == cx::VarDecl || k == cx::FieldDecl || k == cx::ParmDecl) {
      const Tok* b = before(n.begin);
      if (!b) return false;
      if (b->text == "=") return true;
      if (b->text == "(" || b->text == "{") {
        const Tok* name = before(b->begin);
        return name && name->kind == cx::Identifier && name->text == parent->spelling;
      }
      return false;
    }
    return is_expr(k) || (k >= cx::FirstStmt && k <= cx::LastStmt);
  }

  void arms(const Node& sw) {
    const Node* body = nullptr;
    for (const auto& c : sw.children) {
      if (c.kind == cx::CompoundStmt) body = &c;
    }
    if (!body) return;
    std::vector<const Node*> labels;
    for (const auto& c : body->children) {
      const Node* l = &c;
      while (l && (l->kind == cx::CaseStmt || l->kind == cx::DefaultStmt)) {
        labels.push_back(l);
        l = l->children.empty() ? nullptr : &l->children.back();
      }
    }
    for (std::size_t i = 0; i + 1 < labels.size(); i++) {
      if (labels[i]->kind != cx::CaseStmt || !labels[i]->own) continue;
      add("arm", labels[i]->begin, labels[i + 1]->begin, "");
    }
  }

  // A call that is a whole statement: what it returns goes nowhere.
  bool statement(const Node& n, const Node* parent) const {
    if (!parent) return false;
    switch (parent->kind) {
      case cx::CompoundStmt:
        return true;
      case cx::CaseStmt:
      case cx::DefaultStmt:
        return !parent->children.empty() && parent->children.back().begin == n.begin;
      case cx::IfStmt:
      case cx::WhileStmt:
      case cx::ForStmt:
      case cx::CXXForRangeStmt:
        return n.begin >= header(*parent).second;
      case cx::DoStmt:
        return n.end <= last_while(*parent);
      default:
        return false;
    }
  }

  void call(const Node& n) {
    if (n.callee_kind == cx::Constructor || n.callee_kind == cx::Destructor ||
        n.callee_kind == cx::ConversionFunction) {
      return;
    }
    if (n.spelling.rfind("operator", 0) == 0 || n.callee_name.rfind("operator", 0) == 0) return;
    if (ENDS_A_PATH.count(n.callee_name) || ENDS_A_PATH.count(n.spelling)) return;
    const Tok* semi = token(n.end);
    if (!semi || semi->text != ";" || semi->begin != n.end) return;
    add("call", n.begin, semi->end, "{}");
  }

  const std::string& text_;
  std::vector<Tok> toks_;
  std::vector<std::pair<long, long>> macros_;
  const std::vector<std::string>& exclude_;
  std::set<std::string> wanted_;
  std::string enclosing_;
  bool in_function_ = false;
  std::vector<const Node*> stack_;
};

#if defined(__APPLE__)
const std::string& sdk() {
  static const std::string path = [] {
    std::string out;
    if (FILE* p = popen("xcrun --show-sdk-path 2>/dev/null", "r")) {
      char buf[4096];
      while (fgets(buf, sizeof buf, p)) out += buf;
      pclose(p);
    }
    while (!out.empty() && (out.back() == '\n' || out.back() == '\r')) out.pop_back();
    return out;
  }();
  return path;
}
#endif

// The compile command, without the file it compiles, made to parse and
// nothing else: no output, no dependency file, and no warning taken for an
// error.
std::vector<std::string> command_for(const SiteSearch& s) {
  std::vector<std::string> out;
  const auto& a = s.args;
  for (std::size_t i = 0; i < a.size(); i++) {
    const std::string& x = a[i];
    if (i > 0 && (x == "-o" || x == "-MF" || x == "-MT" || x == "-MQ" || x == "--serialize-diagnostics")) {
      i++;
      continue;
    }
    if (i > 0 && (x == "-c" || x == "-MD" || x == "-MMD" || x == "-Werror" || x.rfind("-Werror=", 0) == 0)) continue;
    out.push_back(x);
  }
  if (out.empty()) out.emplace_back(s.c ? "clang" : "clang++");
#if defined(__APPLE__)
  // libclang finds no SDK of its own; a command that names none is given
  // the one the compiler would use.
  bool sysroot = false;
  for (const auto& x : out) sysroot = sysroot || x == "-isysroot" || x.rfind("--sysroot", 0) == 0;
  if (!sysroot && !sdk().empty()) {
    out.emplace_back("-isysroot");
    out.push_back(sdk());
  }
#endif
  // The builtin headers are libclang's own, whichever compiler builds the
  // project.
  if (!cx::resource_dir().empty()) {
    out.emplace_back("-resource-dir");
    out.push_back(cx::resource_dir());
  }
  out.emplace_back("-working-directory");
  out.push_back(s.directory);
  out.emplace_back("-Wno-pragma-once-outside-header");
  if (s.header) {
    out.emplace_back("-x");
    out.emplace_back(s.c ? "c" : "c++");
  }
  out.push_back(s.path);
  return out;
}

std::vector<Tok> tokens(const cx::Api& api, cx::TranslationUnit tu, cx::File file, std::size_t size) {
  std::vector<Tok> out;
  cx::SourceRange all = api.getRange(api.getLocationForOffset(tu, file, 0),
                                     api.getLocationForOffset(tu, file, static_cast<unsigned>(size)));
  cx::Token* ts = nullptr;
  unsigned n = 0;
  api.tokenize(tu, all, &ts, &n);
  for (unsigned i = 0; i < n; i++) {
    int kind = api.getTokenKind(ts[i]);
    if (kind == cx::Comment) continue;
    cx::SourceRange r = api.getTokenExtent(tu, ts[i]);
    cx::File f;
    unsigned line, col, b = 0, e = 0;
    api.getSpellingLocation(api.getRangeStart(r), &f, &line, &col, &b);
    api.getSpellingLocation(api.getRangeEnd(r), &f, &line, &col, &e);
    out.push_back(
        Tok{static_cast<long>(b), static_cast<long>(e), kind, cx::text(api, api.getTokenSpelling(tu, ts[i]))});
  }
  if (ts) api.disposeTokens(tu, ts, n);
  return out;
}

std::vector<Site> number(const SiteSearch& s, std::vector<Candidate> kept) {
  std::string file_hash = sha256_hex(s.text);
  Source src(s.text);
  std::map<std::string, int> seen;
  std::set<std::string> ids;
  std::vector<Site> sites;
  for (const auto& c : kept) {
    std::string id = sha256_hex(file_hash + "|" + std::to_string(c.from) + "|" + std::to_string(c.to) + "|" + c.spell +
                                "|" + c.replacement)
                         .substr(0, 12);
    if (!ids.insert(id).second) continue;
    std::string original = s.text.substr(static_cast<std::size_t>(c.from), static_cast<std::size_t>(c.to - c.from));
    int ordinal = ++seen[c.enclosing + "\x1f" + c.spell + "\x1f" + original];
    Site site;
    site.site_id = id;
    site.file = s.file;
    site.enclosing = c.enclosing;
    site.ordinal = ordinal;
    site.start = src.position(c.from);
    site.end = src.position(c.to);
    site.spell = c.spell;
    site.original = original;
    site.replacement = c.replacement;
    site.reload = s.header ? "dependents" : "module";
    sites.push_back(std::move(site));
  }
  return sites;
}

}  // namespace

bool is_zero(const std::string& t) {
  std::size_t digits = 0;
  while (digits < t.size() && t[digits] == '0') digits++;
  return digits > 0 && t.find_first_of("123456789") == std::string::npos;
}

// Where in the main file a diagnostic points, and the spans it marks.
std::vector<std::pair<long, long>> marked(const cx::Api& api, cx::Diagnostic d, cx::File main) {
  std::vector<std::pair<long, long>> out;
  auto offset = [&](cx::SourceLocation l) {
    cx::File f = nullptr;
    unsigned line, col, at = 0;
    api.getSpellingLocation(l, &f, &line, &col, &at);
    return f == main ? static_cast<long>(at) : -1L;
  };
  long at = offset(api.getDiagnosticLocation(d));
  if (at >= 0) out.emplace_back(at, at + 1);
  for (unsigned i = 0, n = api.getDiagnosticNumRanges(d); i < n; i++) {
    cx::SourceRange r = api.getDiagnosticRange(d, i);
    long b = offset(api.getRangeStart(r)), e = offset(api.getRangeEnd(r));
    if (b >= 0 && e >= b) out.emplace_back(b, e + 1);
  }
  return out;
}

// A `0` the code converts to a pointer is a null pointer, not a number, and
// a `1` there is an integer made a pointer, which a compiler refuses or warns
// of. libclang does not show the conversion, so the file is parsed once more
// with every `0` that is a site made a `1`, and those a new diagnostic marks
// are not sites.
std::vector<Candidate> without_null_pointers(const cx::Api& api, cx::Index index, std::vector<const char*> argv,
                                             const SiteSearch& s, const std::set<std::string>& before,
                                             std::vector<Candidate> found) {
  std::string text = s.text;
  std::set<long> zeros;
  for (const auto& c : found) {
    std::string t = s.text.substr(static_cast<std::size_t>(c.from), static_cast<std::size_t>(c.to - c.from));
    if (c.spell == "literal" && is_zero(t)) zeros.insert(c.from);
  }
  if (zeros.empty()) return found;
  for (long at : zeros) text[static_cast<std::size_t>(at)] = '1';
  argv.insert(argv.end() - 1, "-ferror-limit=0");
  cx::UnsavedFile unsaved{s.path.c_str(), text.data(), static_cast<unsigned long>(text.size())};
  cx::TranslationUnit tu = nullptr;
  if (api.parseTranslationUnit2FullArgv(index, nullptr, argv.data(), static_cast<int>(argv.size()), &unsaved, 1, 0x200,
                                        &tu) != 0 ||
      !tu) {
    return found;
  }
  cx::File main = api.getFile(tu, s.path.c_str());
  std::set<long> refused;
  for (unsigned i = 0, n = api.getNumDiagnostics(tu); i < n; i++) {
    cx::Diagnostic d = api.getDiagnostic(tu, i);
    if (api.getDiagnosticSeverity(d) >= 2 && !before.count(cx::text(api, api.formatDiagnostic(d, 0x1 | 0x2)))) {
      for (const auto& span : marked(api, d, main)) {
        for (auto z = zeros.lower_bound(span.first); z != zeros.end() && *z < span.second; ++z) refused.insert(*z);
      }
    }
    api.disposeDiagnostic(d);
  }
  api.disposeTranslationUnit(tu);
  std::vector<Candidate> kept;
  for (auto& c : found) {
    if (c.spell == "literal" && refused.count(c.from)) continue;
    kept.push_back(std::move(c));
  }
  return kept;
}

Searched find_sites(const cx::Api& api, const SiteSearch& s) {
  Searched out;
  auto skip = [&](const std::string& reason, const std::string& message) {
    out.skipped = Skipped{s.file, reason, message};
    return out;
  };
  std::vector<std::string> argv = command_for(s);
  std::vector<const char*> cargv;
  for (const auto& a : argv) cargv.push_back(a.c_str());
  cx::UnsavedFile unsaved{s.path.c_str(), s.text.data(), static_cast<unsigned long>(s.text.size())};
  cx::Index index = api.createIndex(0, 0);
  cx::TranslationUnit tu = nullptr;
  // The preprocessing record says where macros were invoked; KeepGoing has a
  // parse that meets an error still say where.
  int err = api.parseTranslationUnit2FullArgv(index, nullptr, cargv.data(), static_cast<int>(cargv.size()), &unsaved, 1,
                                              0x01 | 0x200, &tu);
  if (err != 0 || !tu) {
    api.disposeIndex(index);
    return skip("parse_error", "Clang could not parse `" + s.file + "` (error " + std::to_string(err) + ")");
  }
  std::string errors;
  std::set<std::string> diagnosed;
  for (unsigned i = 0, n = api.getNumDiagnostics(tu); i < n; i++) {
    cx::Diagnostic d = api.getDiagnostic(tu, i);
    if (api.getDiagnosticSeverity(d) >= 2) diagnosed.insert(cx::text(api, api.formatDiagnostic(d, 0x1 | 0x2)));
    if (api.getDiagnosticSeverity(d) >= 3 && errors.size() < 2000) {
      errors += (errors.empty() ? "" : "\n") + cx::text(api, api.formatDiagnostic(d, 0x1 | 0x2 | 0x4));
    }
    api.disposeDiagnostic(d);
  }
  if (!errors.empty()) {
    api.disposeTranslationUnit(tu);
    api.disposeIndex(index);
    return skip("parse_error", errors);
  }
  cx::File main = api.getFile(tu, s.path.c_str());
  Builder b(api, tu, main);
  Node root = b.root();
  Walker w(s.text, tokens(api, tu, main, s.text.size()), b.macros, s.spells, s.exclude_calls);
  for (const auto& c : root.children) w.walk(c);
  api.disposeTranslationUnit(tu);
  std::vector<Candidate> found = without_null_pointers(api, index, cargv, s, diagnosed, std::move(w.found));
  api.disposeIndex(index);
  out.sites = number(s, found);
  return out;
}

}  // namespace kalku
