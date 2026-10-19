#include "coverage.hpp"

#include "json.hpp"
#include "test.hpp"

using namespace kalku;

namespace {

Compiler compiler(const std::string& language, const std::string& id, const std::string& version) {
  return Compiler{language, "/no/such/" + id, id, version};
}

}  // namespace

TEST("the compiler is read from what CMake wrote of it") {
  auto c =
      read_compiler("CXX",
                    "set(CMAKE_CXX_COMPILER \"/usr/bin/c++\")\nset(CMAKE_CXX_COMPILER_ARG1 \"\")\n"
                    "set(CMAKE_CXX_COMPILER_ID \"AppleClang\")\nset(CMAKE_CXX_COMPILER_VERSION \"21.0.0.21000334\")\n");
  CHECK(c.has_value());
  if (!c) return;
  CHECK_EQ(c->path, std::string("/usr/bin/c++"));
  CHECK_EQ(c->id, std::string("AppleClang"));
  CHECK_EQ(major_of(c->version), 21);
  CHECK(!read_compiler("C", "set(CMAKE_CXX_COMPILER \"/usr/bin/c++\")").has_value());
}

TEST("a tool is of a release only when its version says that release") {
  CHECK(llvm_of("Apple LLVM version 21.0.0\n  Optimized build.\n", 21));
  CHECK(llvm_of("Homebrew LLVM version 18.1.8\n", 18));
  CHECK(llvm_of("LLVM (http://llvm.org/):\n  LLVM version 17.0.6\n", 17));
  CHECK(!llvm_of("Homebrew LLVM version 18.1.8\n", 1));
  CHECK(!llvm_of("Homebrew LLVM version 18.1.8\n", 19));
  CHECK(!llvm_of("", 18));
}

TEST("a build GCC compiles has its coverage withheld, and says why") {
  std::string why;
  CHECK(!tools_for({compiler("C", "GNU", "13.2.0")}, {}, &why).has_value());
  CHECK(why.find("GNU 13.2.0") != std::string::npos);
  CHECK(why.find("only Clang writes") != std::string::npos);

  why.clear();
  CHECK(!tools_for({compiler("C", "Clang", "18.1.8"), compiler("CXX", "GNU", "13.2.0")}, {}, &why).has_value());
  CHECK(why.find("C++ is compiled by GNU") != std::string::npos);

  why.clear();
  CHECK(!tools_for({}, {}, &why).has_value());
  CHECK(!why.empty());
}

TEST("tools of another release are never taken for the compiler's") {
  std::string why;
  // A release no machine has: nothing found is the answer, not a guess.
  CHECK(!tools_for({compiler("C", "Clang", "999.0.0")}, {"PATH=/usr/bin:/bin"}, &why).has_value());
  CHECK(why.find("LLVM 999") != std::string::npos);

  why.clear();
  CHECK(!tools_for({compiler("C", "Clang", "18.1.8"), compiler("CXX", "Clang", "17.0.6")}, {}, &why).has_value());
  CHECK(why.find("different releases") != std::string::npos);
}

TEST("the lines a test ran are read from the export, in the project's files alone") {
  const std::string lcov =
      "SF:/w/src/calc.c\nFN:3,f\nDA:3,4\nDA:4,0\nDA:5,18446744073709551615\nend_of_record\n"
      "SF:/usr/include/stdio.h\nDA:9,1\nend_of_record\n"
      "SF:/real/w/include/calc.h\nDA:7,2\nDA:8,0\nend_of_record\n"
      "DA:1,1\n";
  Lines got = reached_lines(lcov, {"/real/w", "/w"});
  CHECK_EQ(got.size(), static_cast<std::size_t>(2));
  CHECK(got["src/calc.c"] == (std::set<int>{3, 5}));
  CHECK(got["include/calc.h"] == (std::set<int>{7}));
}

TEST("a line no test ran is credited to every test, and one that ran to those that ran it") {
  Reach r({"a", "b", "c"});
  r.add(0, {{"src/x.c", {1, 2}}});
  r.add(1, {{"src/x.c", {2}}, {"include/x.h", {4}}});
  r.close({{"src/x.c", 3}, {"include/x.h", 4}, {"include/consts.h", 1}});
  auto doc = parse_json(r.entries());
  CHECK(doc.has_value());
  if (!doc) return;
  std::map<std::string, std::string> by;
  for (const auto& e : doc->items) {
    std::string tests;
    for (const auto& t : e.get("tests")->items) tests += t.text;
    by[e.get("file")->text + ":" + std::to_string(e.get("line")->integer)] = tests;
  }
  CHECK_EQ(by["src/x.c:1"], std::string("a"));
  CHECK_EQ(by["src/x.c:2"], std::string("ab"));
  CHECK_EQ(by["src/x.c:3"], std::string("abc"));
  CHECK_EQ(by["include/x.h:4"], std::string("b"));
  CHECK_EQ(by["include/x.h:1"], std::string("abc"));
  CHECK_EQ(by["include/consts.h:1"], std::string("abc"));
  CHECK_EQ(by.size(), static_cast<std::size_t>(8));
}

TEST("a test that left no profile is credited with every line") {
  Reach r({"a", "script"});
  r.add(0, {{"src/x.c", {1}}});
  r.unknown(1);
  r.close({{"src/x.c", 2}});
  auto doc = parse_json(r.packed());
  CHECK(doc.has_value());
  if (!doc) return;
  CHECK_EQ(doc->get("tests")->items.size(), static_cast<std::size_t>(2));
  // Both lines now name both tests, so they are packed together.
  const auto& reached = doc->get("reached")->items;
  CHECK_EQ(reached.size(), static_cast<std::size_t>(1));
  if (reached.empty()) return;
  CHECK_EQ(reached[0].get("lines")->items.size(), static_cast<std::size_t>(2));
  CHECK_EQ(reached[0].get("tests")->items.size(), static_cast<std::size_t>(2));
}
