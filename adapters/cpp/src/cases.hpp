// The test frameworks whose executables hold many cases and are often
// registered with CTest as one test: Catch2 and doctest. Where CTest runs
// such an executable whole, the kalku lists its cases and runs them by name,
// so a case is what judges a wekufe. What each framework says is read here;
// running it is the project's.
#pragma once

#include <map>
#include <string>
#include <vector>

namespace kalku {

enum class Framework { None, Catch2, Catch2Legacy, Doctest };

std::string framework_name(Framework f);

// What an executable said when asked `--version`, which doctest answers
// with its banner and Catch2 refuses.
bool says_doctest(const std::string& version_output);
// What it said when asked `--libidentify`, which only Catch2 answers: v3
// and later, or the v2 that lists its cases another way.
Framework says_catch2(const std::string& libidentify_output);

// The arguments that make the executable list its cases, and its answer
// read. The listing is written to `out`, except by Catch2 v2, which prints
// it. False when the answer is not a list of cases.
std::vector<std::string> listing_args(Framework f, const std::string& out);
struct Listed {
  std::string name;
  // The source file the framework names, as it names it; may be empty.
  std::string file;
};
bool read_listing(Framework f, const std::string& text, std::vector<Listed>* cases, std::string* why);

// The arguments that run the named cases, or every case when none is named,
// with the framework's XML report written to `out`.
std::vector<std::string> run_args(Framework f, const std::vector<std::string>& names, const std::string& out);

struct CaseResult {
  enum class Status { Passed, Failed, Crashed };
  Status status = Status::Passed;
  std::string message;
  long duration_ms = 0;
};

// Each case the report gives a verdict for, by name. A case the report
// opened and never closed ended the executable, and is Crashed.
std::map<std::string, CaseResult> read_report(Framework f, const std::string& xml);

// XML text with its entities decoded, and the value of an attribute in the
// text of one element's opening tag.
std::string decode_xml(const std::string& s);
std::string attribute(const std::string& element, const std::string& name);

}  // namespace kalku
