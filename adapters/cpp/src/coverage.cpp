#include "coverage.hpp"

#include <algorithm>
#include <climits>
#include <cstdlib>
#include <sstream>

#include "json.hpp"
#include "process.hpp"

namespace kalku {

namespace {

// The value of `set(<key> "<value>")` in a file CMake wrote.
std::string set_value(const std::string& text, const std::string& key) {
  std::string open = "set(" + key + " \"";
  auto at = text.find(open);
  if (at == std::string::npos) return "";
  at += open.size();
  auto end = text.find('"', at);
  return end == std::string::npos ? "" : text.substr(at, end - at);
}

bool is_clang(const std::string& id) { return id == "Clang" || id == "AppleClang"; }

std::string dir_of(const std::string& path) {
  auto slash = path.find_last_of('/');
  return slash == std::string::npos ? "" : path.substr(0, slash);
}

std::string trimmed(std::string s) {
  while (!s.empty() && (s.back() == '\n' || s.back() == '\r' || s.back() == ' ')) s.pop_back();
  return s;
}

std::string real(const std::string& path) {
  char buf[PATH_MAX];
  return realpath(path.c_str(), buf) ? std::string(buf) : "";
}

std::string language_name(const std::string& language) { return language == "CXX" ? "C++" : language; }

}  // namespace

std::optional<Compiler> read_compiler(const std::string& language, const std::string& cmake_text) {
  Compiler c;
  c.language = language;
  c.path = set_value(cmake_text, "CMAKE_" + language + "_COMPILER");
  c.id = set_value(cmake_text, "CMAKE_" + language + "_COMPILER_ID");
  c.version = set_value(cmake_text, "CMAKE_" + language + "_COMPILER_VERSION");
  if (c.path.empty()) return std::nullopt;
  return c;
}

int major_of(const std::string& version) { return std::atoi(version.c_str()); }

bool llvm_of(const std::string& version_output, int major) {
  const std::string key = "LLVM version ";
  auto at = version_output.find(key);
  if (at == std::string::npos || major <= 0) return false;
  std::string rest = version_output.substr(at + key.size());
  return major_of(rest) == major && rest.find('.') != std::string::npos;
}

std::optional<Tools> tools_for(const std::vector<Compiler>& compilers, const std::vector<std::string>& env,
                               std::string* why) {
  if (compilers.empty()) {
    *why = "CMake names no C or C++ compiler for the build, so there is no instrumentation to read";
    return std::nullopt;
  }
  for (const auto& c : compilers) {
    if (!is_clang(c.id)) {
      *why = "the project's " + language_name(c.language) + " is compiled by " +
             (c.id.empty() ? std::string("a compiler CMake did not identify") : c.id + " " + c.version) +
             ", and coverage per test is read from Clang's own instrumentation, which only Clang writes";
      return std::nullopt;
    }
  }
  int major = major_of(compilers[0].version);
  for (const auto& c : compilers) {
    if (major_of(c.version) != major || c.id != compilers[0].id) {
      *why = "the project's C and C++ compilers are of different releases (" + compilers[0].id + " " +
             compilers[0].version + " and " + c.id + " " + c.version +
             "), and one profile is read with the tools of one release";
      return std::nullopt;
    }
  }
  // Beside the compiler itself; then the toolchain Xcode selects, which is
  // the one Apple's compiler is; then on the PATH, by the release's name
  // first.
  std::vector<Tools> candidates;
  for (const auto& c : compilers) {
    std::string dir = dir_of(real(c.path));
    if (!dir.empty()) candidates.push_back({dir + "/llvm-profdata", dir + "/llvm-cov"});
  }
  if (compilers[0].id == "AppleClang") {
    std::string p = trimmed(output_of({"xcrun", "--find", "llvm-profdata"}, "/", env));
    std::string v = trimmed(output_of({"xcrun", "--find", "llvm-cov"}, "/", env));
    if (!p.empty() && !v.empty()) candidates.push_back({p, v});
  }
  std::string m = std::to_string(major);
  candidates.push_back({"llvm-profdata-" + m, "llvm-cov-" + m});
  candidates.push_back({"llvm-profdata", "llvm-cov"});
  for (const auto& t : candidates) {
    if (t.profdata.find('/') == std::string::npos && !on_path(t.profdata, env)) continue;
    if (llvm_of(output_of({t.profdata, "--version"}, "/", env), major) &&
        llvm_of(output_of({t.cov, "--version"}, "/", env), major)) {
      return t;
    }
  }
  *why = "no llvm-profdata and llvm-cov of LLVM " + m + ", the release of " + compilers[0].path + " (" +
         compilers[0].id + " " + compilers[0].version +
         "), were found beside it or on the PATH, and a profile is only read by the tools of the release that wrote "
         "it";
  return std::nullopt;
}

Lines reached_lines(const std::string& lcov, const std::vector<std::string>& roots) {
  Lines out;
  std::string file;
  bool ours = false;
  std::istringstream in(lcov);
  std::string line;
  while (std::getline(in, line)) {
    if (line.rfind("SF:", 0) == 0) {
      std::string path = line.substr(3);
      ours = false;
      for (const auto& r : roots) {
        if (path.rfind(r + "/", 0) == 0) {
          file = path.substr(r.size() + 1);
          ours = true;
          break;
        }
      }
    } else if (line.rfind("DA:", 0) == 0 && ours) {
      auto comma = line.find(',');
      if (comma == std::string::npos) continue;
      int number = std::atoi(line.c_str() + 3);
      std::string count = line.substr(comma + 1);
      count = count.substr(0, count.find(','));
      if (number > 0 && !count.empty() && count != "0" && count.find_first_not_of("0123456789") == std::string::npos) {
        out[file].insert(number);
      }
    } else if (line == "end_of_record") {
      ours = false;
    }
  }
  return out;
}

void Reach::add(std::size_t test, const Lines& lines) {
  for (const auto& f : lines) {
    auto& by = by_line_[f.first];
    for (int n : f.second) by[n].insert(test);
  }
}

void Reach::close(const std::map<std::string, int>& files) {
  for (const auto& f : files) {
    auto by = by_line_.find(f.first);
    for (int n = 1; n <= f.second; n++) {
      if (by == by_line_.end() || !by->second.count(n)) unreached_[f.first].push_back(n);
    }
  }
  for (auto& f : by_line_) {
    for (auto& l : f.second) l.second.insert(everywhere_.begin(), everywhere_.end());
  }
}

// Each file's lines grouped by the tests that reach them, the lines no test
// was seen to run among them, credited to every test.
std::map<std::string, std::map<std::set<std::size_t>, std::vector<int>>> Reach::groups() const {
  std::set<std::size_t> all;
  for (std::size_t i = 0; i < tests_.size(); i++) all.insert(i);
  std::map<std::string, std::map<std::set<std::size_t>, std::vector<int>>> out;
  for (const auto& f : by_line_) {
    for (const auto& l : f.second) out[f.first][l.second].push_back(l.first);
  }
  for (const auto& f : unreached_) {
    auto& lines = out[f.first][all];
    lines.insert(lines.end(), f.second.begin(), f.second.end());
    std::sort(lines.begin(), lines.end());
  }
  return out;
}

std::string Reach::packed() const {
  std::string out = "{\"tests\":[";
  for (std::size_t i = 0; i < tests_.size(); i++) out += (i ? "," : "") + quote(tests_[i]);
  out += "],\"reached\":[";
  bool first = true;
  for (const auto& f : groups()) {
    for (const auto& g : f.second) {
      if (g.first.empty()) continue;
      out += first ? "" : ",";
      first = false;
      out += "{\"file\":" + quote(f.first) + ",\"lines\":[";
      for (std::size_t i = 0; i < g.second.size(); i++) out += (i ? "," : "") + std::to_string(g.second[i]);
      out += "],\"tests\":[";
      bool one = true;
      for (std::size_t t : g.first) {
        out += (one ? "" : ",") + std::to_string(t);
        one = false;
      }
      out += "]}";
    }
  }
  return out + "]}";
}

std::string Reach::entries() const {
  std::map<std::pair<std::string, int>, const std::set<std::size_t>*> lines;
  auto all = groups();
  for (const auto& f : all) {
    for (const auto& g : f.second) {
      for (int n : g.second) lines[{f.first, n}] = &g.first;
    }
  }
  std::string out = "[";
  bool first = true;
  for (const auto& l : lines) {
    if (l.second->empty()) continue;
    out += first ? "" : ",";
    first = false;
    out += "{\"file\":" + quote(l.first.first) + ",\"line\":" + std::to_string(l.first.second) + ",\"tests\":[";
    bool one = true;
    for (std::size_t t : *l.second) {
      out += (one ? "" : ",") + quote(tests_[t]);
      one = false;
    }
    out += "]}";
  }
  return out + "]";
}

}  // namespace kalku
