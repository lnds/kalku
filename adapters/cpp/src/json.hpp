// JSON as the protocol uses it: a value read from a line, and canonical
// text written in the order fields are added.
#pragma once

#include <cstdint>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace kalku {

struct Json {
  enum class Kind { Null, Bool, Integer, Real, String, Array, Object };
  Kind kind = Kind::Null;
  bool boolean = false;
  std::int64_t integer = 0;
  double real = 0;
  std::string text;
  std::vector<Json> items;
  // Fields in the order they were read; a repeated key keeps the last.
  std::vector<std::pair<std::string, Json>> fields;

  bool is_null() const { return kind == Kind::Null; }
  bool is_string() const { return kind == Kind::String; }
  bool is_integer() const { return kind == Kind::Integer; }
  bool is_array() const { return kind == Kind::Array; }
  bool is_object() const { return kind == Kind::Object; }
  bool is_bool() const { return kind == Kind::Bool; }

  // The field of an object, or nullptr when it has none by that name.
  const Json* get(const std::string& key) const;
};

// Parses one JSON text, or says why it is not one.
std::optional<Json> parse_json(const std::string& text, std::string* why = nullptr);

// A string in raw UTF-8 with only `"`, `\` and control characters escaped.
std::string quote(const std::string& s);

// An object written in canonical form: fields in the order they are added.
class Writer {
 public:
  Writer() { out_ = "{"; }
  static Writer message(const std::string& type, std::int64_t id);

  Writer& str(const std::string& key, const std::string& value);
  Writer& integer(const std::string& key, std::int64_t value);
  Writer& boolean(const std::string& key, bool value);
  Writer& strs(const std::string& key, const std::vector<std::string>& values);
  Writer& raw(const std::string& key, const std::string& json);
  Writer& objects(const std::string& key, const std::vector<std::string>& items);
  std::string done() const { return out_ + "}"; }

 private:
  void key(const std::string& k);
  std::string out_;
};

}  // namespace kalku
