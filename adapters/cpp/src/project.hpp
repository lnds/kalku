// One CMake project, as the kalku works on it: a copy of its tree in the
// reni, configured and built in a build tree of its own beside it, and its
// tests as CTest lists them. Under make every worker's copy and build tree
// are copied from one base, built once for them all, which no wekufe is
// ever written into.
#pragma once

#include <atomic>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "cases.hpp"
#include "json.hpp"
#include "process.hpp"
#include "protocol.hpp"

namespace kalku {

struct Test {
  std::string id;
  std::string name;
  // Where the test is declared, relative to the root.
  std::string file;
  // Its number in CTest's list, which is how a run selects it.
  int number = 0;
  // For a case of an executable the kalku runs itself: its name there, and
  // the executable, among the project's suites.
  std::string test_case;
  int suite = -1;
};

// A CTest test that runs a Catch2 or doctest executable whole, whose cases
// the kalku lists and runs by name.
struct Suite {
  std::string name;
  Framework framework = Framework::None;
  std::string executable;
  std::string directory;
  std::vector<std::string> env;
  std::size_t cases = 0;
};

struct TestResult {
  enum class Status { Passed, Failed, Crashed, NotRun };
  Status status = Status::NotRun;
  std::string message;
  long duration_ms = 0;
};

struct TestRun {
  std::map<std::string, TestResult> by_id;
  bool cancelled = false;
  // Why there is no report to read, when there is none.
  std::string broken;
};

// How a file is compiled, as the build says it.
struct Compile {
  std::string directory;
  std::vector<std::string> args;
  // The command compiles C, so a header read with it is read as C.
  bool c = false;
};

// How bringing the base up to date ended: done; `Unshared`, configured
// for a generator other than make, so each worker builds for itself; or
// stopped at the step named.
enum class BaseStage { Done, Unshared, Copy, Configure, Build };

class Project {
 public:
  Project(const Hello& h, std::vector<std::string> cmake_args);

  const std::string& root() const { return root_; }
  const std::string& work() const { return work_; }
  const std::vector<std::string>& env() const { return env_; }

  // Puts the project's tree in the copy: everything but what git keeps,
  // build trees, and the reni.
  bool copy(std::string* why);
  bool copy_files(const std::vector<std::string>& files, std::string* why);

  // True when the build tree can be copied from the base: make dates its
  // outputs and nothing else, so a copy that keeps the dates is up to date.
  // Ninja keeps the commands it ran in its own logs, and rebuilds anything
  // moved.
  bool shares_build() const;
  // Brings the base up to date with the project, building it if need be,
  // and makes this worker's copy and build tree a copy of it. `ran` is the
  // command that failed, if one did.
  BaseStage from_base(Ran* ran, std::string* why);

  Ran configure(const std::atomic<bool>* cancel = nullptr);
  Ran build(const std::atomic<bool>* cancel = nullptr);
  // An executable is not linked again when only a shared library it loads
  // changed, unless the tree copies that library; after the first build,
  // one that does is configured and built again to link them all.
  Ran settle_links(const std::atomic<bool>* cancel = nullptr);
  bool configured() const;
  bool list_tests(std::string* why);

  const std::vector<Test>& tests() const { return tests_; }
  const Test* lookup(const std::string& id) const;
  TestRun run_tests(const std::vector<const Test*>& tests, const std::atomic<bool>* cancel);

  // A file of the copy, and writing one there.
  std::optional<std::string> read(const std::string& file) const;
  bool write(const std::string& file, const std::string& text);

  // The command that compiles a file, or, for a header or a file the build
  // does not compile alone, one that compiles a file beside it.
  std::optional<Compile> compile_of(const std::string& file) const;
  int translation_units() const;

  // Says a message about the copy with the project's own paths.
  std::string named(const std::string& message) const;
  std::vector<std::string> differences() const;
  // What became of each test executable the kalku asked for its cases, one
  // sentence each.
  const std::vector<std::string>& listed() const { return listed_; }

 private:
  std::string root_;
  std::string reni_;
  std::string worker_;
  std::string work_;
  std::string build_;
  std::string base_work_;
  std::string base_build_;
  std::string junit_;
  // Where a framework writes its list of cases, or its report of a run.
  std::string cases_xml_;
  std::vector<std::string> env_;
  std::vector<std::string> cmake_args_;
  std::string generator() const;
  Ran configure_in(const std::string& work, const std::string& build, const std::atomic<bool>* cancel);
  Ran build_in(const std::string& build, const std::atomic<bool>* cancel);
  Ran settle_links_in(const std::string& work, const std::string& build, const std::atomic<bool>* cancel);
  // The options say how a change to a shared library relinks.
  bool links_chosen() const;
  // Paths of the base, as this worker's copy and build tree name them.
  std::vector<std::pair<std::string, std::string>> rebased() const;

  // The cases of a test that runs one executable of a framework the kalku
  // lists; none, with why, when it cannot list them.
  std::vector<Test> cases_of(const Test& test, const Json& entry, std::string* why);
  void run_ctest(const std::vector<const Test*>& tests, const std::atomic<bool>* cancel, TestRun& out);
  void run_cases(int suite, const std::vector<const Test*>& tests, const std::atomic<bool>* cancel, TestRun& out);

  // The copy and its build tree came from the base.
  bool shared_ = false;
  std::vector<Test> tests_;
  std::vector<Suite> suites_;
  std::vector<std::string> listed_;
  // What each executable answered when asked what it is.
  std::map<std::string, Framework> probed_;
  std::map<std::string, std::size_t> by_id_;
  long stamp_ = 0;
  long last_build_ = 0;
};

// True for a file kalku reads as C or C++ source, or as a header of either.
bool is_source_file(const std::string& file);
// True for a C source file; `.C` is C++.
bool is_c_file(const std::string& file);
bool is_header(const std::string& file);
// True for a file that is part of a suite, which is the oracle.
bool is_test_file(const std::string& file);

}  // namespace kalku
