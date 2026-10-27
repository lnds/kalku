#include "protocol.hpp"

#include <dirent.h>

#include <algorithm>

#include "framing.hpp"
#include "json.hpp"
#include "sha256.hpp"
#include "source.hpp"
#include "test.hpp"

using namespace kalku;
using kalku::testing::here;
using kalku::testing::read;

namespace {

const std::string FIXTURES = "../../docs/protocol/fixtures/";

std::vector<std::string> lines_of(const std::string& name) {
  std::string text = read(here(FIXTURES + name));
  std::vector<std::string> out;
  std::size_t from = 0;
  while (from < text.size()) {
    std::size_t to = text.find('\n', from);
    if (to == std::string::npos) to = text.size();
    if (to > from) out.push_back(text.substr(from, to - from));
    from = to + 1;
  }
  return out;
}

std::vector<std::string> files_in(const std::string& dir) {
  std::vector<std::string> out;
  if (DIR* d = opendir(here(FIXTURES + dir).c_str())) {
    while (dirent* e = readdir(d)) {
      std::string n = e->d_name;
      if (n.size() > 7 && n.substr(n.size() - 7) == ".ndjson") out.push_back(n);
    }
    closedir(d);
  }
  std::sort(out.begin(), out.end());
  return out;
}

}  // namespace

TEST("every request fixture decodes") {
  int seen = 0;
  for (const auto& f : files_in("kalku/requests")) {
    for (const auto& line : lines_of("kalku/requests/" + f)) {
      seen++;
      DecodeError e;
      if (!decode(line, &e)) kalku::testing::failed(f, e.kind + ": " + e.detail + "\n  " + line);
    }
  }
  CHECK(seen >= 10);
}

// `return` is cast only where a project names it, so the kalku says it can
// and reads a request that asks for it.
TEST("ready announces return, and a sites request may name it") {
  std::string r = ready(1, "0", "clang", {"cast"});
  CHECK(r.find("\"literal\",\"call\",\"return\"]") != std::string::npos);
  DecodeError e;
  auto req =
      decode("{\"type\":\"sites\",\"id\":4,\"files\":[\"a.c\"],\"spells\":[\"return\"],\"exclude_calls\":[]}", &e);
  CHECK(req.has_value());
}

TEST("a malformed request is refused with the kind its fixture names") {
  int seen = 0;
  for (const auto& entry : lines_of("invalid/kalku_requests.ndjson")) {
    auto v = parse_json(entry);
    CHECK(v.has_value());
    std::string expect = v->get("expect")->text;
    std::string line = v->get("line")->text;
    DecodeError e;
    auto r = decode(line, &e);
    CHECK(!r.has_value());
    CHECK_EQ(e.kind, expect);
    seen++;
  }
  CHECK(seen >= 10);
}

TEST("one oversized line costs only itself, and the line after it is read") {
  for (const auto& entry : lines_of("invalid/framing.ndjson")) {
    auto v = parse_json(entry);
    std::string input = v->get("input")->text;
    std::size_t max = static_cast<std::size_t>(v->get("max")->integer);
    std::string expect = v->get("expect")->text;
    std::size_t at = 0;
    LineReader r(
        [&](char* buf, std::size_t n) -> long {
          // A byte at a time, so a line is never read whole by luck.
          (void)n;
          if (at >= input.size()) return 0;
          buf[0] = input[at++];
          return 1;
        },
        max);
    std::string line;
    LineReader::Got first = r.next(line);
    CHECK_EQ(first == LineReader::Got::TooLong ? std::string("line_too_long") : std::string("ok"), expect);
    CHECK(r.next(line) == LineReader::Got::Line);
    CHECK_EQ(line, std::string("{\"type\":\"bye\",\"id\":8}"));
  }
}

TEST("every reply this kalku writes is the canonical form of a fixture") {
  // The replies are written by this kalku's own code, field by field, and a
  // fixture written by it must read back as itself.
  std::vector<std::string> written = {
      bye(7),
      prepared(2, 41200, 312),
      reset_done(10, true, 140),
      aborted(9, 5, true),
      error(5, "load_failed", "could not restore MyApp.Parser", true),
      error(5, "unknown_test", "no test test/my_app/parser_test.exs:99", false),
  };
  std::string fixtures;
  for (const auto& f : files_in("kalku/replies")) {
    for (const auto& line : lines_of("kalku/replies/" + f)) fixtures += line + "\n";
  }
  for (const auto& w : written) {
    if (fixtures.find(w + "\n") == std::string::npos) kalku::testing::failed("replies", "no fixture is " + w);
  }
}

TEST("a site reads back as the fixture it was decoded from") {
  for (const auto& line : lines_of("shapes/site.ndjson")) {
    std::string request = "{\"type\":\"cast\",\"id\":1,\"wekufe\":\"w\",\"site\":" + line + ",\"tests\":[]}";
    DecodeError e;
    auto r = decode(request, &e);
    CHECK(r.has_value());
    if (r) CHECK_EQ(r->cast.site.json(), line);
  }
}

TEST("a position is in bytes and characters, past letters of every width") {
  std::string text = "const char* s = \"ñ😀\";\nint n = 1 < 2;\n";
  Source src(text);
  long at = static_cast<long>(text.find('<'));
  Position p = src.position(at);
  CHECK_EQ(p.line, 2);
  CHECK_EQ(p.col, 11);
  CHECK_EQ(p.byte, at);
  long quote = static_cast<long>(text.find('"'));
  Position q = src.position(quote + 1 + 2 + 4);
  CHECK_EQ(q.col, 20);
}

TEST("sha256 is the standard digest") {
  CHECK_EQ(sha256_hex(""), std::string("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
  CHECK_EQ(sha256_hex("abc"), std::string("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
}

TEST("json strings escape only what they must") {
  CHECK_EQ(quote("a\"b\\c\nd\x01ñ"), std::string("\"a\\\"b\\\\c\\nd\\u0001ñ\""));
  auto v = parse_json("{\"a\":\"\\u00f1\\ud83d\\ude00\",\"b\":[1,2.5,true,null]}");
  CHECK(v.has_value());
  CHECK_EQ(v->get("a")->text, std::string("ñ😀"));
  CHECK(v->get("b")->items[0].is_integer());
  CHECK(!v->get("b")->items[1].is_integer());
}
