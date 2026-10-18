#include "json.hpp"

#include <cerrno>
#include <cstdio>
#include <cstdlib>

namespace kalku {

const Json* Json::get(const std::string& key) const {
  const Json* found = nullptr;
  for (const auto& f : fields) {
    if (f.first == key) found = &f.second;
  }
  return found;
}

namespace {

class Parser {
 public:
  explicit Parser(const std::string& t) : t_(t) {}

  bool document(Json& out) {
    space();
    if (!value(out, 0)) return false;
    space();
    if (i_ != t_.size()) return fail("more than one value on the line");
    return true;
  }

  std::string why;

 private:
  bool fail(const std::string& w) {
    if (why.empty()) why = w + " at byte " + std::to_string(i_);
    return false;
  }

  void space() {
    while (i_ < t_.size() && (t_[i_] == ' ' || t_[i_] == '\t' || t_[i_] == '\n' || t_[i_] == '\r')) i_++;
  }

  bool literal(const char* word) {
    std::size_t n = std::char_traits<char>::length(word);
    if (t_.compare(i_, n, word) != 0) return fail("not a JSON value");
    i_ += n;
    return true;
  }

  bool value(Json& out, int depth) {
    if (depth > 512) return fail("nested too deeply");
    if (i_ >= t_.size()) return fail("unexpected end");
    char c = t_[i_];
    switch (c) {
      case '{':
        return object(out, depth);
      case '[':
        return array(out, depth);
      case '"':
        out.kind = Json::Kind::String;
        return string(out.text);
      case 't':
        out.kind = Json::Kind::Bool;
        out.boolean = true;
        return literal("true");
      case 'f':
        out.kind = Json::Kind::Bool;
        out.boolean = false;
        return literal("false");
      case 'n':
        out.kind = Json::Kind::Null;
        return literal("null");
      default:
        if (c == '-' || (c >= '0' && c <= '9')) return number(out);
        return fail("not a JSON value");
    }
  }

  bool object(Json& out, int depth) {
    out.kind = Json::Kind::Object;
    i_++;
    space();
    if (i_ < t_.size() && t_[i_] == '}') {
      i_++;
      return true;
    }
    for (;;) {
      space();
      if (i_ >= t_.size() || t_[i_] != '"') return fail("expected a key");
      std::string k;
      if (!string(k)) return false;
      space();
      if (i_ >= t_.size() || t_[i_] != ':') return fail("expected `:`");
      i_++;
      space();
      Json v;
      if (!value(v, depth + 1)) return false;
      out.fields.emplace_back(std::move(k), std::move(v));
      space();
      if (i_ < t_.size() && t_[i_] == ',') {
        i_++;
        continue;
      }
      if (i_ < t_.size() && t_[i_] == '}') {
        i_++;
        return true;
      }
      return fail("expected `,` or `}`");
    }
  }

  bool array(Json& out, int depth) {
    out.kind = Json::Kind::Array;
    i_++;
    space();
    if (i_ < t_.size() && t_[i_] == ']') {
      i_++;
      return true;
    }
    for (;;) {
      space();
      Json v;
      if (!value(v, depth + 1)) return false;
      out.items.push_back(std::move(v));
      space();
      if (i_ < t_.size() && t_[i_] == ',') {
        i_++;
        continue;
      }
      if (i_ < t_.size() && t_[i_] == ']') {
        i_++;
        return true;
      }
      return fail("expected `,` or `]`");
    }
  }

  static void utf8(std::string& out, unsigned cp) {
    if (cp < 0x80) {
      out += static_cast<char>(cp);
    } else if (cp < 0x800) {
      out += static_cast<char>(0xC0 | (cp >> 6));
      out += static_cast<char>(0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
      out += static_cast<char>(0xE0 | (cp >> 12));
      out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
      out += static_cast<char>(0x80 | (cp & 0x3F));
    } else {
      out += static_cast<char>(0xF0 | (cp >> 18));
      out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
      out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
      out += static_cast<char>(0x80 | (cp & 0x3F));
    }
  }

  bool hex4(unsigned& cp) {
    if (i_ + 4 > t_.size()) return fail("a short \\u escape");
    cp = 0;
    for (int k = 0; k < 4; k++) {
      char h = t_[i_++];
      cp <<= 4;
      if (h >= '0' && h <= '9') {
        cp |= static_cast<unsigned>(h - '0');
      } else if (h >= 'a' && h <= 'f') {
        cp |= static_cast<unsigned>(h - 'a' + 10);
      } else if (h >= 'A' && h <= 'F') {
        cp |= static_cast<unsigned>(h - 'A' + 10);
      } else {
        return fail("a bad \\u escape");
      }
    }
    return true;
  }

  bool string(std::string& out) {
    i_++;
    while (i_ < t_.size()) {
      unsigned char c = static_cast<unsigned char>(t_[i_]);
      if (c == '"') {
        i_++;
        return true;
      }
      if (c < 0x20) return fail("a control character in a string");
      if (c != '\\') {
        out += static_cast<char>(c);
        i_++;
        continue;
      }
      i_++;
      if (i_ >= t_.size()) break;
      char e = t_[i_++];
      switch (e) {
        case '"':
          out += '"';
          break;
        case '\\':
          out += '\\';
          break;
        case '/':
          out += '/';
          break;
        case 'b':
          out += '\b';
          break;
        case 'f':
          out += '\f';
          break;
        case 'n':
          out += '\n';
          break;
        case 'r':
          out += '\r';
          break;
        case 't':
          out += '\t';
          break;
        case 'u': {
          unsigned cp = 0;
          if (!hex4(cp)) return false;
          if (cp >= 0xD800 && cp <= 0xDBFF && i_ + 1 < t_.size() && t_[i_] == '\\' && t_[i_ + 1] == 'u') {
            i_ += 2;
            unsigned low = 0;
            if (!hex4(low)) return false;
            cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
          }
          utf8(out, cp);
          break;
        }
        default:
          return fail("a bad escape");
      }
    }
    return fail("an unterminated string");
  }

  bool number(Json& out) {
    std::size_t start = i_;
    bool fraction = false;
    if (t_[i_] == '-') i_++;
    if (i_ >= t_.size() || t_[i_] < '0' || t_[i_] > '9') return fail("a bad number");
    if (t_[i_] == '0') {
      i_++;
    } else {
      while (i_ < t_.size() && t_[i_] >= '0' && t_[i_] <= '9') i_++;
    }
    if (i_ < t_.size() && t_[i_] == '.') {
      fraction = true;
      i_++;
      if (i_ >= t_.size() || t_[i_] < '0' || t_[i_] > '9') return fail("a bad number");
      while (i_ < t_.size() && t_[i_] >= '0' && t_[i_] <= '9') i_++;
    }
    if (i_ < t_.size() && (t_[i_] == 'e' || t_[i_] == 'E')) {
      fraction = true;
      i_++;
      if (i_ < t_.size() && (t_[i_] == '+' || t_[i_] == '-')) i_++;
      if (i_ >= t_.size() || t_[i_] < '0' || t_[i_] > '9') return fail("a bad number");
      while (i_ < t_.size() && t_[i_] >= '0' && t_[i_] <= '9') i_++;
    }
    std::string digits = t_.substr(start, i_ - start);
    if (!fraction) {
      errno = 0;
      char* end = nullptr;
      long long n = std::strtoll(digits.c_str(), &end, 10);
      if (errno == 0 && end && *end == '\0') {
        out.kind = Json::Kind::Integer;
        out.integer = n;
        return true;
      }
    }
    out.kind = Json::Kind::Real;
    out.real = std::strtod(digits.c_str(), nullptr);
    return true;
  }

  const std::string& t_;
  std::size_t i_ = 0;
};

}  // namespace

std::optional<Json> parse_json(const std::string& text, std::string* why) {
  Parser p(text);
  Json out;
  if (!p.document(out)) {
    if (why) *why = p.why;
    return std::nullopt;
  }
  return out;
}

std::string quote(const std::string& s) {
  std::string out = "\"";
  for (unsigned char c : s) {
    switch (c) {
      case '"':
        out += "\\\"";
        break;
      case '\\':
        out += "\\\\";
        break;
      case '\n':
        out += "\\n";
        break;
      case '\r':
        out += "\\r";
        break;
      case '\t':
        out += "\\t";
        break;
      case '\b':
        out += "\\b";
        break;
      case '\f':
        out += "\\f";
        break;
      default:
        if (c < 0x20) {
          char buf[8];
          std::snprintf(buf, sizeof buf, "\\u%04x", c);
          out += buf;
        } else {
          out += static_cast<char>(c);
        }
    }
  }
  return out + "\"";
}

Writer Writer::message(const std::string& type, std::int64_t id) {
  Writer w;
  w.str("type", type);
  w.integer("id", id);
  return w;
}

void Writer::key(const std::string& k) {
  if (out_.size() > 1) out_ += ',';
  out_ += quote(k);
  out_ += ':';
}

Writer& Writer::str(const std::string& k, const std::string& v) {
  key(k);
  out_ += quote(v);
  return *this;
}

Writer& Writer::integer(const std::string& k, std::int64_t v) {
  key(k);
  out_ += std::to_string(v);
  return *this;
}

Writer& Writer::boolean(const std::string& k, bool v) {
  key(k);
  out_ += v ? "true" : "false";
  return *this;
}

Writer& Writer::strs(const std::string& k, const std::vector<std::string>& vs) {
  key(k);
  out_ += '[';
  for (std::size_t i = 0; i < vs.size(); i++) {
    if (i) out_ += ',';
    out_ += quote(vs[i]);
  }
  out_ += ']';
  return *this;
}

Writer& Writer::raw(const std::string& k, const std::string& json) {
  key(k);
  out_ += json;
  return *this;
}

Writer& Writer::objects(const std::string& k, const std::vector<std::string>& items) {
  key(k);
  out_ += '[';
  for (std::size_t i = 0; i < items.size(); i++) {
    if (i) out_ += ',';
    out_ += items[i];
  }
  out_ += ']';
  return *this;
}

}  // namespace kalku
