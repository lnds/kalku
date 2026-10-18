// One CMake project, as the kalku works on it: a copy of its tree in the
// reni, configured and built in a build tree of its own beside it, and its
// tests as CTest lists them.
#pragma once

#include <atomic>
#include <map>
#include <optional>
#include <string>
#include <vector>

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
};

struct TestResult {
  enum class Status { Passed, Failed, Crashed, NotRun };
  Status status = Status::NotRun;
  std::string message;
  long duration_ms = 0;
};

struct TestRun {
  std::map<std::string, TestResult> by_name;
  bool cancelled = false;
  // Why there is no report to read, when there is none.
  std::string broken;
};

// How a file is compiled, as the build says it.
struct Compile {
  std::string directory;
  std::vector<std::string> args;
};

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

  Ran configure(const std::atomic<bool>* cancel = nullptr);
  Ran build(const std::atomic<bool>* cancel = nullptr);
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

 private:
  std::string root_;
  std::string reni_;
  std::string work_;
  std::string build_;
  std::string junit_;
  std::vector<std::string> env_;
  std::vector<std::string> cmake_args_;
  // The build tree dates its outputs finer than to the second.
  bool precise() const;

  std::vector<Test> tests_;
  std::map<std::string, std::size_t> by_id_;
  long stamp_ = 0;
  long last_build_ = 0;
};

// True for a file kalku reads as C++ source or header.
bool is_cpp_file(const std::string& file);
bool is_header(const std::string& file);
// True for a file that is part of a suite, which is the oracle.
bool is_test_file(const std::string& file);

}  // namespace kalku
