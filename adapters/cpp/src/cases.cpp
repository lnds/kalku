#include "cases.hpp"

#include <cstdlib>
#include <set>
#include <sstream>

namespace kalku {

namespace {

std::string trim(const std::string& s) {
  auto from = s.find_first_not_of(" \t\r\n");
  if (from == std::string::npos) return "";
  auto to = s.find_last_not_of(" \t\r\n");
  return s.substr(from, to - from + 1);
}

// The text inside the first `<tag>` of `xml` from `at`, decoded.
std::string text_of(const std::string& xml, const std::string& tag, std::size_t at = 0) {
  auto open = xml.find("<" + tag, at);
  if (open == std::string::npos) return "";
  auto start = xml.find('>', open);
  if (start == std::string::npos || xml[start - 1] == '/') return "";
  auto close = xml.find("</" + tag + ">", start);
  if (close == std::string::npos) return "";
  return decode_xml(xml.substr(start + 1, close - start - 1));
}

// One element named `tag`: the text of its opening tag, and what it holds;
// `closed` is false when the report ends inside it.
struct Element {
  std::string open;
  std::string body;
  bool closed = true;
};

std::vector<Element> elements(const std::string& xml, const std::string& tag) {
  std::vector<Element> out;
  std::string start = "<" + tag;
  std::size_t at = 0;
  while ((at = xml.find(start, at)) != std::string::npos) {
    char next = at + start.size() < xml.size() ? xml[at + start.size()] : '\0';
    if (next != ' ' && next != '>' && next != '/') {
      at += start.size();
      continue;
    }
    auto open_end = xml.find('>', at);
    if (open_end == std::string::npos) break;
    Element e;
    e.open = xml.substr(at, open_end - at);
    if (xml[open_end - 1] != '/') {
      auto close = xml.find("</" + tag + ">", open_end);
      e.closed = close != std::string::npos;
      e.body = xml.substr(open_end + 1, e.closed ? close - open_end - 1 : std::string::npos);
      at = e.closed ? close : xml.size();
    } else {
      at = open_end;
    }
    out.push_back(e);
  }
  return out;
}

// Catch2 reads a comma as the end of a name, brackets as a tag, a leading
// `~` or `exclude:` as a negation and `*` as a wildcard; a backslash takes
// any character as itself, so each name is matched exactly.
std::string catch2_spec(const std::string& name) {
  std::string out;
  for (std::size_t i = 0; i < name.size(); i++) {
    char c = name[i];
    if (i == 0 || c == '\\' || c == ',' || c == '[' || c == ']' || c == '*' || c == '"' || c == '~') out += '\\';
    out += c;
  }
  return out;
}

// doctest reads a comma as the end of a name; a backslash takes a comma or
// itself as such. Its wildcards, `*` and `?`, match themselves too.
std::string doctest_spec(const std::string& name) {
  std::string out;
  for (char c : name) {
    if (c == '\\' || c == ',') out += '\\';
    out += c;
  }
  return out;
}

std::string joined(const std::vector<std::string>& names, std::string (*spec)(const std::string&)) {
  std::string out;
  for (const auto& n : names) out += (out.empty() ? "" : ",") + spec(n);
  return out;
}

// Why a case failed, from the first thing its report says failed.
std::string failure_of(const std::string& body) {
  for (const auto& e : elements(body, "Expression")) {
    if (attribute(e.open, "success") != "false") continue;
    std::string where = attribute(e.open, "filename");
    std::string line = attribute(e.open, "line");
    std::string what = attribute(e.open, "type") + "( " + trim(text_of(e.body, "Original")) + " )";
    std::string expanded = trim(text_of(e.body, "Expanded"));
    if (!expanded.empty()) what += " with " + expanded;
    return "it failed: " + what + (where.empty() ? "" : " at " + where + ":" + line);
  }
  for (const auto& e : elements(body, "Exception")) return "it threw: " + trim(decode_xml(e.body));
  return "it failed";
}

}  // namespace

std::string decode_xml(const std::string& s) {
  std::string out;
  for (std::size_t i = 0; i < s.size(); i++) {
    if (s[i] != '&') {
      out += s[i];
      continue;
    }
    std::size_t semi = s.find(';', i);
    if (semi == std::string::npos) {
      out += s[i];
      continue;
    }
    std::string ent = s.substr(i + 1, semi - i - 1);
    if (ent == "amp") {
      out += '&';
    } else if (ent == "lt") {
      out += '<';
    } else if (ent == "gt") {
      out += '>';
    } else if (ent == "quot") {
      out += '"';
    } else if (ent == "apos") {
      out += '\'';
    } else if (!ent.empty() && ent[0] == '#') {
      long cp = ent.size() > 1 && (ent[1] == 'x' || ent[1] == 'X') ? std::strtol(ent.c_str() + 2, nullptr, 16)
                                                                   : std::strtol(ent.c_str() + 1, nullptr, 10);
      if (cp > 0 && cp < 0x80) out += static_cast<char>(cp);
    } else {
      out += s.substr(i, semi - i + 1);
    }
    i = semi;
  }
  return out;
}

std::string attribute(const std::string& element, const std::string& name) {
  std::string key = " " + name + "=\"";
  auto at = element.find(key);
  if (at == std::string::npos) return "";
  at += key.size();
  auto end = element.find('"', at);
  return end == std::string::npos ? "" : decode_xml(element.substr(at, end - at));
}

std::string framework_name(Framework f) {
  switch (f) {
    case Framework::Catch2:
    case Framework::Catch2Legacy:
      return "Catch2";
    case Framework::Doctest:
      return "doctest";
    case Framework::None:
      break;
  }
  return "";
}

bool says_doctest(const std::string& version_output) {
  return version_output.find("[doctest] doctest version is \"") != std::string::npos;
}

Framework says_catch2(const std::string& libidentify_output) {
  std::stringstream in(libidentify_output);
  std::string line, framework, version;
  while (std::getline(in, line)) {
    auto colon = line.find(':');
    if (colon == std::string::npos) continue;
    std::string key = trim(line.substr(0, colon));
    std::string value = trim(line.substr(colon + 1));
    if (key == "framework") framework = value;
    if (key == "version") version = value;
  }
  if (framework.rfind("Catch", 0) != 0 || version.empty()) return Framework::None;
  int major = std::atoi(version.c_str());
  if (major >= 3) return Framework::Catch2;
  if (major == 2) return Framework::Catch2Legacy;
  return Framework::None;
}

std::vector<std::string> listing_args(Framework f, const std::string& out) {
  switch (f) {
    case Framework::Catch2:
      return {"--list-tests", "--reporter", "xml", "--out", out};
    case Framework::Catch2Legacy:
      return {"--list-test-names-only"};
    case Framework::Doctest:
      return {"--list-test-cases", "--reporters=xml", "--out=" + out};
    case Framework::None:
      break;
  }
  return {};
}

bool read_listing(Framework f, const std::string& text, std::vector<Listed>* cases, std::string* why) {
  cases->clear();
  if (f == Framework::Catch2Legacy) {
    // One name to a line; a name that starts with `#` is printed quoted.
    std::stringstream in(text);
    std::string line;
    while (std::getline(in, line)) {
      if (!line.empty() && line.back() == '\r') line.pop_back();
      if (line.empty()) continue;
      if (line.size() > 2 && line[0] == '"' && line[1] == '#' && line.back() == '"') {
        line = line.substr(1, line.size() - 2);
      }
      cases->push_back(Listed{line, ""});
    }
  } else if (f == Framework::Catch2) {
    if (text.find("<MatchingTests") == std::string::npos) {
      *why = "its list of cases is not Catch2's XML";
      return false;
    }
    for (const auto& e : elements(text, "TestCase")) {
      if (!e.closed) {
        *why = "its list of cases ends inside one";
        return false;
      }
      cases->push_back(Listed{text_of(e.body, "Name"), trim(text_of(e.body, "File"))});
    }
  } else if (f == Framework::Doctest) {
    if (text.find("<doctest") == std::string::npos) {
      *why = "its list of cases is not doctest's XML";
      return false;
    }
    for (const auto& e : elements(text, "TestCase")) {
      if (attribute(e.open, "skipped") == "true") continue;
      cases->push_back(Listed{attribute(e.open, "name"), attribute(e.open, "filename")});
    }
  } else {
    *why = "it is not a framework the kalku lists";
    return false;
  }
  if (cases->empty()) {
    *why = "it listed no cases";
    return false;
  }
  std::set<std::string> seen;
  for (const auto& c : *cases) {
    if (c.name.empty()) {
      *why = "it listed a case with no name";
      return false;
    }
    // A name chooses every case it names, so one that names two cannot
    // tell them apart.
    if (!seen.insert(c.name).second) {
      *why = "it has two cases named `" + c.name + "`";
      return false;
    }
  }
  return true;
}

std::vector<std::string> run_args(Framework f, const std::vector<std::string>& names, const std::string& out) {
  std::vector<std::string> args;
  if (f == Framework::Catch2 || f == Framework::Catch2Legacy) {
    if (!names.empty()) args.push_back(joined(names, catch2_spec));
    for (const char* a : {"--reporter", "xml", "--durations", "yes", "--out"}) args.emplace_back(a);
    args.push_back(out);
  } else if (f == Framework::Doctest) {
    if (!names.empty()) args.push_back("--test-case=" + joined(names, doctest_spec));
    args.emplace_back("--reporters=xml");
    args.emplace_back("--duration=true");
    args.push_back("--out=" + out);
  }
  return args;
}

std::map<std::string, CaseResult> read_report(Framework f, const std::string& xml) {
  std::map<std::string, CaseResult> out;
  bool doctest = f == Framework::Doctest;
  for (const auto& e : elements(xml, "TestCase")) {
    // doctest names every case it was not asked to run, with no verdict.
    if (attribute(e.open, "skipped") == "true") continue;
    std::string name = attribute(e.open, "name");
    CaseResult r;
    std::string crash;
    for (const auto& c : elements(e.body, doctest ? "Exception" : "FatalErrorCondition")) {
      if (!doctest || attribute(c.open, "crash") == "true") crash = trim(decode_xml(c.body));
    }
    std::string verdict;
    std::string seconds;
    if (doctest) {
      for (const auto& o : elements(e.body, "OverallResultsAsserts")) {
        verdict = attribute(o.open, "test_case_success");
        seconds = attribute(o.open, "duration");
      }
    } else {
      for (const auto& o : elements(e.body, "OverallResult")) {
        verdict = attribute(o.open, "success");
        seconds = attribute(o.open, "durationInSeconds");
      }
    }
    r.duration_ms = static_cast<long>(std::strtod(seconds.c_str(), nullptr) * 1000 + 0.5);
    if (!crash.empty()) {
      r.status = CaseResult::Status::Crashed;
      r.message = "it ended with " + crash;
    } else if (!e.closed || verdict.empty()) {
      r.status = CaseResult::Status::Crashed;
      r.message = "it ended without a verdict";
    } else if (verdict == "true") {
      r.status = CaseResult::Status::Passed;
    } else {
      r.status = CaseResult::Status::Failed;
      r.message = failure_of(e.body);
    }
    out[name] = r;
  }
  return out;
}

}  // namespace kalku
