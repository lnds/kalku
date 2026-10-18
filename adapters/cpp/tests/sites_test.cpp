#include "sites.hpp"

#include <dirent.h>

#include <algorithm>
#include <cstdlib>
#include <fstream>

#include "clang.hpp"
#include "project.hpp"
#include "test.hpp"

using namespace kalku;
using kalku::testing::here;
using kalku::testing::read;

namespace {

std::vector<std::string> fixtures() {
  std::vector<std::string> out;
  std::string root = here("testdata/sites");
  DIR* d = opendir(root.c_str());
  if (!d) return out;
  while (dirent* e = readdir(d)) {
    std::string spell = e->d_name;
    if (spell == "." || spell == "..") continue;
    DIR* inner = opendir((root + "/" + spell).c_str());
    if (!inner) continue;
    while (dirent* f = readdir(inner)) {
      std::string name = f->d_name;
      if (is_cpp_file(name)) out.push_back(spell + "/" + name);
    }
    closedir(inner);
  }
  closedir(d);
  std::sort(out.begin(), out.end());
  return out;
}

// libclang is needed to read C++ at all: without one this machine cannot
// run these, which on CI is a failure and elsewhere is said and skipped.
const cx::Api* clang_or_skip() {
  std::string why;
  const cx::Api* api = cx::load(&why);
  if (!api) {
    if (std::getenv("CI")) kalku::testing::failed("libclang", why);
    std::cerr << "  skipped: " << why << "\n";
  }
  return api;
}

Searched search(const cx::Api& api, const std::string& name, const std::string& text) {
  SiteSearch s;
  s.file = name;
  s.path = here("testdata/sites/" + name);
  s.text = text;
  s.args = {"clang++", "-std=c++17"};
  s.directory = here("testdata/sites");
  s.header = is_header(name);
  s.spells = SPELLS;
  s.exclude_calls = {"audit::*"};
  return find_sites(api, s);
}

}  // namespace

// The sites of every fixture, against a golden file beside it. A golden is
// only as good as its review: regenerate with UPDATE_GOLDEN=1, then read the
// diff.
TEST("every fixture's sites are its golden's") {
  const cx::Api* api = clang_or_skip();
  if (!api) return;
  auto all = fixtures();
  CHECK(all.size() >= 9);
  for (const auto& name : all) {
    std::string path = here("testdata/sites/" + name);
    Searched got = search(*api, name, read(path));
    if (got.skipped) {
      kalku::testing::failed(name, got.skipped->reason + ": " + got.skipped->message);
      continue;
    }
    std::string text;
    for (const auto& s : got.sites) text += s.json() + "\n";
    std::string golden = path.substr(0, path.find_last_of('.')) + ".sites.ndjson";
    if (std::getenv("UPDATE_GOLDEN")) std::ofstream(golden, std::ios::binary) << text;
    std::string want = read(golden);
    if (text != want) kalku::testing::failed(name, "the sites are not the golden's\n got:\n" + text + "want:\n" + want);
  }
}

TEST("a site's bytes are the text it was found in") {
  const cx::Api* api = clang_or_skip();
  if (!api) return;
  for (const auto& name : fixtures()) {
    std::string text = read(here("testdata/sites/" + name));
    for (const auto& s : search(*api, name, text).sites) {
      CHECK_EQ(
          text.substr(static_cast<std::size_t>(s.start.byte), static_cast<std::size_t>(s.end->byte - s.start.byte)),
          *s.original);
    }
  }
}

TEST("a file that does not parse is skipped, saying why") {
  const cx::Api* api = clang_or_skip();
  if (!api) return;
  Searched got = search(*api, "compare/broken.cpp", "int f( { return 1 < 2; }\n");
  CHECK(got.skipped.has_value());
  if (got.skipped) CHECK_EQ(got.skipped->reason, std::string("parse_error"));
}

TEST("a file is read as it is given, whatever is on the disk") {
  const cx::Api* api = clang_or_skip();
  if (!api) return;
  Searched got = search(*api, "compare/branches.cpp", "int f(int a) { return a < 2; }\n");
  CHECK(!got.skipped.has_value());
  int compares = 0;
  for (const auto& s : got.sites) compares += s.spell == "compare";
  CHECK_EQ(compares, 1);
}

TEST("test files are the suite, and are known by their names and places") {
  CHECK(is_test_file("tests/math.cpp"));
  CHECK(is_test_file("src/math_test.cpp"));
  CHECK(is_test_file("src/MathTest.cpp"));
  CHECK(is_test_file("test_math.cc"));
  CHECK(!is_test_file("src/math.cpp"));
  CHECK(!is_test_file("src/attestation.cpp"));
  CHECK(is_header("include/ring.hpp"));
  CHECK(is_cpp_file("src/a.cc"));
  CHECK(!is_cpp_file("src/a.c"));
}
