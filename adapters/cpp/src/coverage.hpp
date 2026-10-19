// Which tests reach which lines, from Clang's source-based coverage: the
// project built a second time with `-fprofile-instr-generate
// -fcoverage-mapping`, each test run alone with a profile of its own, and
// LLVM's own tools reading back the lines it ran.
#pragma once

#include <map>
#include <optional>
#include <set>
#include <string>
#include <vector>

namespace kalku {

// A compiler as CMake found it, from `CMakeFiles/<version>/CMake<LANG>Compiler.cmake`.
struct Compiler {
  std::string language;
  std::string path;
  std::string id;
  std::string version;
};

std::optional<Compiler> read_compiler(const std::string& language, const std::string& cmake_text);

// The major version in a version string: "21.0.0.21000334" → 21.
int major_of(const std::string& version);

// True when a tool's `--version` says it is of LLVM `major`, the release
// whose profile format the compiler writes.
bool llvm_of(const std::string& version_output, int major);

// The programs that read a profile, of the compiler's own release.
struct Tools {
  std::string profdata;
  std::string cov;
};

// Every compiler of a build is Clang of one release, and the tools of that
// release are found: then the tools; else none, and why, in a sentence.
std::optional<Tools> tools_for(const std::vector<Compiler>& compilers, const std::vector<std::string>& env,
                               std::string* why);

// The lines with a count above zero in an `llvm-cov export -format=lcov`,
// by file relative to one of `roots`; files under none are not the
// project's and are left out.
using Lines = std::map<std::string, std::set<int>>;
Lines reached_lines(const std::string& lcov, const std::vector<std::string>& roots);

// Coverage gathered test by test, then said as the protocol says it.
class Reach {
 public:
  explicit Reach(std::vector<std::string> tests) : tests_(std::move(tests)) {}
  void add(std::size_t test, const Lines& lines);
  // A test whose runs left no profile: what it reaches is not known, so it
  // is credited with every line.
  void unknown(std::size_t test) { everywhere_.insert(test); }
  // A line no test was seen to run — a constant, what the compiler
  // evaluated, code nothing calls — is credited to every test, which is
  // what judges it with no coverage at all. `files` are the project's
  // sources with how many lines each has.
  void close(const std::map<std::string, int>& files);

  // The packed form: every test once, and the lines of each file that the
  // same tests reach together.
  std::string packed() const;
  // The same entries, one per line, as a JSON array.
  std::string entries() const;

 private:
  std::vector<std::string> tests_;
  std::map<std::string, std::map<int, std::set<std::size_t>>> by_line_;
  std::set<std::size_t> everywhere_;
  std::map<std::string, std::vector<int>> unreached_;
  std::map<std::string, std::map<std::set<std::size_t>, std::vector<int>>> groups() const;
};

}  // namespace kalku
