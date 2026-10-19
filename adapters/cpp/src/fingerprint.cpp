#include "fingerprint.hpp"

#include <dirent.h>
#include <sys/stat.h>
#include <sys/utsname.h>
#include <unistd.h>

#include <algorithm>
#include <climits>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <set>
#include <sstream>

#include "json.hpp"
#include "process.hpp"
#include "project.hpp"
#include "sha256.hpp"
#include "version.hpp"

namespace kalku {

namespace {

const char* const CLIENT = "client-kalku";
// What kalku keeps of its own runs in a project, which no test reads and
// every run changes.
const char* const KALKU_OWN = ".kalku";

// What CMake, the build tool and CTest keep in a build tree for themselves.
// Each follows from the CMakeLists and the options, which every fingerprint
// covers, and is written again, not always the same, whenever CMake
// configures afresh.
const std::set<std::string> GENERATED = {"CMakeFiles",
                                         "Testing",
                                         ".cmake",
                                         "CMakeCache.txt",
                                         "build.ninja",
                                         "Makefile",
                                         "cmake_install.cmake",
                                         "CTestTestfile.cmake",
                                         "compile_commands.json",
                                         "DartConfiguration.tcl",
                                         "install_manifest.txt"};

std::string slurp(const std::string& path, bool* ok = nullptr) {
  std::ifstream in(path, std::ios::binary);
  if (ok) *ok = static_cast<bool>(in);
  std::ostringstream s;
  s << in.rdbuf();
  return s.str();
}

bool starts_with(const std::string& s, const std::string& head) { return s.rfind(head, 0) == 0; }

// `path` is `dir` or lies below it.
bool within(const std::string& path, const std::string& dir) {
  return path == dir || (starts_with(path, dir) && path.size() > dir.size() && path[dir.size()] == '/');
}

std::vector<std::string> names_in(const std::string& dir) {
  std::vector<std::string> out;
  DIR* d = opendir(dir.c_str());
  if (!d) return out;
  while (dirent* e = readdir(d)) {
    std::string name = e->d_name;
    if (name != "." && name != "..") out.push_back(name);
  }
  closedir(d);
  std::sort(out.begin(), out.end());
  return out;
}

std::string absolute(const std::string& path, const std::string& dir) {
  return path.empty() || path[0] == '/' ? path : dir + "/" + path;
}

std::string text_of(const Json* j) { return j && j->is_string() ? j->text : ""; }

// What a build tree's cache holds for `name`, whatever its type.
std::string cache_entry(const std::string& build, const std::string& name) {
  std::stringstream lines(slurp(build + "/CMakeCache.txt"));
  std::string line;
  while (std::getline(lines, line)) {
    if (starts_with(line, name + ":") && line.find('=') != std::string::npos) return line.substr(line.find('=') + 1);
  }
  return "";
}

// A JSON value as one canonical text.
void canonical(const Json& j, std::string& out) {
  switch (j.kind) {
    case Json::Kind::Null:
      out += "null";
      break;
    case Json::Kind::Bool:
      out += j.boolean ? "true" : "false";
      break;
    case Json::Kind::Integer:
      out += std::to_string(j.integer);
      break;
    case Json::Kind::Real: {
      std::ostringstream s;
      s.precision(17);
      s << j.real;
      out += s.str();
      break;
    }
    case Json::Kind::String:
      out += quote(j.text);
      break;
    case Json::Kind::Array:
      out += "[";
      for (std::size_t i = 0; i < j.items.size(); i++) {
        if (i) out += ",";
        canonical(j.items[i], out);
      }
      out += "]";
      break;
    case Json::Kind::Object:
      out += "{";
      for (std::size_t i = 0; i < j.fields.size(); i++) {
        if (i) out += ",";
        out += quote(j.fields[i].first) + ":";
        canonical(j.fields[i].second, out);
      }
      out += "}";
      break;
  }
}

// A target's description without what CMake writes in an order of its own
// choosing: the targets it depends on, which a fingerprint takes in by
// their own descriptions, and where in the CMakeLists each part was
// declared, which every fingerprint takes in with the CMakeLists.
Json settled(Json j) {
  static const std::set<std::string> unordered = {"backtrace",
                                                  "backtraceGraph",
                                                  "dependencies",
                                                  "compileDependencies",
                                                  "linkDependencies",
                                                  "orderDependencies",
                                                  "objectDependencies",
                                                  "interfaceCompileDependencies",
                                                  "interfaceLinkDependencies"};
  j.fields.erase(
      std::remove_if(j.fields.begin(), j.fields.end(), [](const auto& f) { return unordered.count(f.first) > 0; }),
      j.fields.end());
  for (auto& f : j.fields) f.second = settled(std::move(f.second));
  for (auto& i : j.items) i = settled(std::move(i));
  return j;
}

// Every string a JSON value holds.
void strings_of(const Json& j, std::vector<std::string>& out) {
  if (j.is_string()) out.push_back(j.text);
  for (const auto& i : j.items) strings_of(i, out);
  for (const auto& f : j.fields) strings_of(f.second, out);
}

// What the file API says of one target.
struct Target {
  std::string name;
  std::string type;
  // Its description, with the reni's paths named as every run names them.
  std::string text;
  std::vector<std::string> artifacts;
  std::string objects;
  std::vector<std::string> dependencies;
  // Files it is built from that no compile of its own reads: objects or
  // libraries given by path, a linker script, a source with no compile.
  std::vector<std::string> given;
  bool compiles = false;
};

struct Compiled {
  std::string command;
  std::string file;
  std::vector<std::string> inputs;
  bool traced = false;
};

}  // namespace

void ask_for_codemodel(const std::string& build) {
  std::string dir = build + "/.cmake/api/v1/query/" + CLIENT;
  std::string at;
  std::stringstream parts(dir);
  std::string part;
  while (std::getline(parts, part, '/')) {
    if (part.empty()) continue;
    at += "/" + part;
    mkdir(at.c_str(), 0755);
  }
  for (const char* kind : {"codemodel-v2", "toolchains-v1"}) {
    std::ofstream touch(dir + "/" + kind, std::ios::app);
  }
}

std::vector<std::string> depfile_inputs(const std::string& text) {
  // One logical line: a backslash before a newline continues it.
  std::string line;
  for (std::size_t i = 0; i < text.size(); i++) {
    if (text[i] == '\\' && i + 1 < text.size() && text[i + 1] == '\n') {
      line += ' ';
      i++;
    } else if (text[i] == '\\' && i + 2 < text.size() && text[i + 1] == '\r' && text[i + 2] == '\n') {
      line += ' ';
      i += 2;
    } else if (text[i] == '\n') {
      break;
    } else {
      line += text[i];
    }
  }
  std::vector<std::string> out;
  std::string word;
  bool target = true;
  auto end_word = [&]() {
    if (word.empty()) return;
    if (target) {
      if (word.back() == ':') {
        target = false;
      }
    } else {
      out.push_back(word);
    }
    word.clear();
  };
  for (std::size_t i = 0; i < line.size(); i++) {
    char c = line[i];
    if (c == '\\' && i + 1 < line.size() && (line[i + 1] == ' ' || line[i + 1] == '#' || line[i + 1] == '\\')) {
      word += line[++i];
    } else if (c == '$' && i + 1 < line.size() && line[i + 1] == '$') {
      word += '$';
      i++;
    } else if (c == ' ' || c == '\t' || c == '\r') {
      end_word();
    } else if (c == ':' && target && (i + 1 == line.size() || line[i + 1] == ' ' || line[i + 1] == '\t')) {
      word += c;
      end_word();
      target = false;
    } else {
      word += c;
    }
  }
  end_word();
  return out;
}

std::map<std::string, std::vector<std::string>> ninja_deps(const std::string& text) {
  std::map<std::string, std::vector<std::string>> out;
  std::stringstream lines(text);
  std::string line;
  std::vector<std::string>* current = nullptr;
  while (std::getline(lines, line)) {
    if (!line.empty() && line.back() == '\r') line.pop_back();
    if (line.empty()) {
      current = nullptr;
      continue;
    }
    if (line[0] == ' ' || line[0] == '\t') {
      if (current) current->push_back(line.substr(line.find_first_not_of(" \t")));
      continue;
    }
    auto at = line.rfind(": #deps ");
    current = nullptr;
    if (at == std::string::npos || line.find("(VALID)", at) == std::string::npos) continue;
    current = &out[line.substr(0, at)];
  }
  return out;
}

namespace {

// Everything a run of the fingerprints reads, each file hashed once.
class Tracer {
 public:
  Tracer(std::vector<std::pair<std::string, std::string>> names, std::string work, std::string build)
      : names_(std::move(names)), work_(std::move(work)), build_(std::move(build)) {
    std::sort(names_.begin(), names_.end(),
              [](const auto& a, const auto& b) { return a.first.size() > b.first.size(); });
  }

  // The reni's paths, as every run names them.
  std::string named(std::string s) const {
    for (const auto& n : names_) {
      std::size_t at = 0;
      while ((at = s.find(n.first, at)) != std::string::npos) {
        s.replace(at, n.first.size(), n.second);
        at += n.second.size();
      }
    }
    return s;
  }

  std::string real(const std::string& path) {
    auto it = real_.find(path);
    if (it != real_.end()) return it->second;
    char buf[PATH_MAX];
    std::string r = realpath(path.c_str(), buf) ? std::string(buf) : path;
    real_[path] = r;
    return r;
  }

  // What a file holds. A text file of the build tree can name the reni,
  // and is read as every run would name it.
  std::string content(const std::string& path) {
    auto it = content_.find(path);
    if (it != content_.end()) return it->second;
    struct stat st;
    std::string h;
    if (lstat(path.c_str(), &st) != 0) {
      h = "absent";
    } else if (S_ISLNK(st.st_mode)) {
      char target[PATH_MAX];
      ssize_t n = readlink(path.c_str(), target, sizeof target - 1);
      h = "link " + (n < 0 ? std::string("?") : named(std::string(target, static_cast<std::size_t>(n))));
    } else if (S_ISDIR(st.st_mode)) {
      h = "directory";
    } else {
      bool ok = false;
      std::string data = slurp(path, &ok);
      if (!ok) {
        h = "unreadable";
      } else {
        if (within(path, build_) && data.find('\0') == std::string::npos) data = named(data);
        h = sha256_hex(data);
      }
    }
    content_[path] = h;
    return h;
  }

  // One line per file: its name and what it holds.
  std::string file_line(const std::string& path) { return named(path) + " " + content(path) + "\n"; }

  // Every file under a directory of the project, and, under the build tree,
  // only what the build itself did not write: not the objects, CMake's own
  // state, the logs, or a target's artifacts.
  std::string tree(const std::string& dir, const std::set<std::string>& artifacts) {
    auto it = trees_.find(dir);
    if (it != trees_.end()) return it->second;
    std::string lines;
    walk(dir, within(dir, build_), artifacts, lines);
    std::string h = sha256_hex(lines);
    trees_[dir] = h;
    return h;
  }

 private:
  void walk(const std::string& dir, bool built, const std::set<std::string>& artifacts, std::string& lines) {
    for (const auto& name : names_in(dir)) {
      std::string path = dir + "/" + name;
      struct stat st;
      if (lstat(path.c_str(), &st) != 0) continue;
      if (built && (GENERATED.count(name) || starts_with(name, ".ninja_"))) continue;
      if (built ? artifacts.count(path) > 0 : name == KALKU_OWN) continue;
      if (S_ISDIR(st.st_mode)) {
        walk(path, built, artifacts, lines);
      } else {
        lines += file_line(path);
      }
    }
  }

  std::vector<std::pair<std::string, std::string>> names_;
  std::string work_;
  std::string build_;
  std::map<std::string, std::string> real_;
  std::map<std::string, std::string> content_;
  std::map<std::string, std::string> trees_;
};

// The paths under `prefix` a text names: each runs to the next character a
// path in a command or a list does not hold.
std::vector<std::string> paths_in(const std::string& text, const std::string& prefix) {
  std::vector<std::string> out;
  std::size_t at = 0;
  while ((at = text.find(prefix, at)) != std::string::npos) {
    std::size_t end = text.find_first_of(" \t\n\"';:,=", at);
    if (end == std::string::npos) end = text.size();
    std::string path = text.substr(at, end - at);
    while (path.size() > prefix.size() && path.back() == '/') path.pop_back();
    if (path.size() == prefix.size() || path[prefix.size()] == '/') out.push_back(path);
    at = end;
  }
  return out;
}

bool has_property(const Json& entry, const std::string& name) {
  if (const Json* props = entry.get("properties")) {
    for (const auto& p : props->items) {
      if (text_of(p.get("name")) == name) return true;
    }
  }
  return false;
}

std::string property(const Json& entry, const std::string& name) {
  if (const Json* props = entry.get("properties")) {
    for (const auto& p : props->items) {
      if (text_of(p.get("name")) == name) return text_of(p.get("value"));
    }
  }
  return "";
}

}  // namespace

std::vector<std::string> Project::fingerprints() {
  std::vector<std::string> none(tests_.size());
  auto give_up = [&](const std::string& why) {
    std::cerr << "kalku-cpp: no test has a fingerprint: " << why << "\n";
    return none;
  };
  std::vector<std::pair<std::string, std::string>> names = {
      {work_, "<work>"},
      {build_, "<build>"},
      {reni_ + "/work/" + worker_, "<work>"},
      {reni_ + "/build/" + worker_, "<build>"},
      {base_work_, "<work>"},
      {base_build_, "<build>"},
      {reni_ + "/work/base", "<work>"},
      {reni_ + "/build/base", "<build>"},
  };
  Tracer tr(names, work_, build_);

  // The file API's answer to the query this kalku wrote before configuring.
  std::string reply = build_ + "/.cmake/api/v1/reply";
  std::string index_name;
  for (const auto& n : names_in(reply)) {
    if (starts_with(n, "index-") && n.size() > 11 && n.compare(n.size() - 5, 5, ".json") == 0) index_name = n;
  }
  if (index_name.empty()) return give_up("CMake's file API wrote no reply");
  auto index = parse_json(slurp(reply + "/" + index_name));
  const Json* ours = index && index->get("reply") ? index->get("reply")->get(CLIENT) : nullptr;
  const Json* model_ref = ours ? ours->get("codemodel-v2") : nullptr;
  const Json* chains_ref = ours ? ours->get("toolchains-v1") : nullptr;
  if (!model_ref || !model_ref->get("jsonFile") || !chains_ref || !chains_ref->get("jsonFile")) {
    return give_up("CMake's file API did not describe the build");
  }
  auto model = parse_json(slurp(reply + "/" + text_of(model_ref->get("jsonFile"))));
  const Json* configs = model ? model->get("configurations") : nullptr;
  if (!configs || configs->items.size() != 1) return give_up("the build does not have exactly one configuration");
  std::string top_source = model->get("paths") ? text_of(model->get("paths")->get("source")) : "";
  std::string top_build = model->get("paths") ? text_of(model->get("paths")->get("build")) : "";
  if (top_source.empty() || top_build.empty()) return give_up("the file API names no source or build directory");

  std::map<std::string, Target> targets;
  std::map<std::string, std::string> by_artifact;
  std::set<std::string> artifacts;
  if (const Json* ts = configs->items[0].get("targets")) {
    for (const auto& ref : ts->items) {
      std::string id = text_of(ref.get("id"));
      bool ok = false;
      std::string text = slurp(reply + "/" + text_of(ref.get("jsonFile")), &ok);
      auto doc = ok ? parse_json(text) : std::nullopt;
      if (!doc) return give_up("the file API's description of `" + text_of(ref.get("name")) + "` cannot be read");
      Target t;
      t.name = text_of(doc->get("name"));
      t.type = text_of(doc->get("type"));
      std::string described;
      canonical(settled(*doc), described);
      t.text = tr.named(described);
      std::string dir = doc->get("paths") ? text_of(doc->get("paths")->get("build")) : ".";
      t.objects = top_build + "/" + (dir == "." || dir.empty() ? "" : dir + "/") + "CMakeFiles/" + t.name + ".dir/";
      if (const Json* as = doc->get("artifacts")) {
        for (const auto& a : as->items) {
          std::string path = tr.real(absolute(text_of(a.get("path")), top_build));
          t.artifacts.push_back(path);
          by_artifact[path] = id;
          artifacts.insert(path);
        }
      }
      if (const Json* ds = doc->get("dependencies")) {
        for (const auto& d : ds->items) t.dependencies.push_back(text_of(d.get("id")));
      }
      if (const Json* ss = doc->get("sources")) {
        for (const auto& s : ss->items) {
          if (s.get("compileGroupIndex")) {
            t.compiles = true;
          } else {
            t.given.push_back(absolute(text_of(s.get("path")), top_source));
          }
        }
      }
      for (const char* step : {"link", "archive"}) {
        const Json* how = doc->get(step);
        const Json* fragments = how ? how->get("commandFragments") : nullptr;
        if (!fragments) continue;
        for (const auto& f : fragments->items) {
          if (text_of(f.get("role")) != "libraries") continue;
          std::string lib = text_of(f.get("fragment"));
          if (lib.empty() || lib[0] == '-') continue;
          std::string path = absolute(lib, top_build + "/" + (dir == "." || dir.empty() ? "" : dir));
          struct stat st;
          if (stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode)) t.given.push_back(path);
        }
      }
      targets[id] = t;
    }
  }

  // Each object the build compiles, with what its compile read.
  bool ok = false;
  auto commands = parse_json(slurp(build_ + "/compile_commands.json", &ok));
  if (!ok || !commands || !commands->is_array()) return give_up("the build wrote no compile_commands.json");
  std::string generator = cache_entry(build_, "CMAKE_GENERATOR");
  bool ninja = starts_with(generator, "Ninja");
  if (!ninja && generator != "Unix Makefiles") return give_up("the " + generator + " generator keeps no depfiles");
  std::map<std::string, std::vector<std::string>> logged;
  if (ninja) {
    std::string out = reni_ + "/deps-" + worker_ + ".txt";
    Ran r = run_to({cache_entry(build_, "CMAKE_MAKE_PROGRAM"), "-C", build_, "-t", "deps"}, build_, env_, out);
    if (!r.ok()) return give_up("`ninja -t deps` failed");
    logged = ninja_deps(slurp(out));
    unlink(out.c_str());
  }
  std::map<std::string, Compiled> objects;
  std::set<std::string> read_by_compiles;
  for (const auto& e : commands->items) {
    std::string dir = text_of(e.get("directory"));
    std::string output = text_of(e.get("output"));
    if (output.empty()) return give_up("compile_commands.json does not say what each compile writes");
    Compiled c;
    c.file = tr.real(absolute(text_of(e.get("file")), dir));
    std::string command = text_of(e.get("command"));
    if (const Json* args = e.get("arguments")) {
      for (const auto& a : args->items) command += a.text + "\n";
    }
    c.command = tr.named(command);
    // Make names an object from the top of the build tree, wherever the
    // compile runs.
    std::string object = absolute(output, top_build);
    struct stat st;
    if (stat(object.c_str(), &st) != 0) object = absolute(output, dir);
    std::vector<std::string> raw;
    if (ninja) {
      std::string key = starts_with(object, top_build + "/") ? object.substr(top_build.size() + 1) : object;
      auto it = logged.find(key);
      if (it != logged.end()) {
        raw = it->second;
        c.traced = true;
      }
    } else {
      bool read = false;
      std::string depfile = slurp(object + ".d", &read);
      if (read) {
        raw = depfile_inputs(depfile);
        c.traced = !raw.empty();
      }
    }
    for (const auto& r : raw) c.inputs.push_back(tr.real(absolute(r, dir)));
    read_by_compiles.insert(c.file);
    read_by_compiles.insert(c.inputs.begin(), c.inputs.end());
    objects[object] = c;
  }

  // A target's own part: its description, and what its objects and the
  // files it is given hold. Empty when one of its objects cannot be traced.
  std::map<std::string, std::string> own;
  for (const auto& kv : targets) {
    const Target& t = kv.second;
    std::string lines = t.type + " " + t.text + "\n";
    bool traced = true;
    int compiled = 0;
    for (auto it = objects.lower_bound(t.objects); it != objects.end() && starts_with(it->first, t.objects); ++it) {
      const Compiled& c = it->second;
      if (!c.traced) traced = false;
      compiled++;
      std::vector<std::string> inputs = c.inputs;
      inputs.push_back(c.file);
      std::sort(inputs.begin(), inputs.end());
      inputs.erase(std::unique(inputs.begin(), inputs.end()), inputs.end());
      lines += "object " + tr.named(it->first) + "\n" + c.command + "\n";
      for (const auto& in : inputs) lines += tr.file_line(in);
    }
    if (t.compiles && compiled == 0) traced = false;
    for (const auto& g : t.given) {
      // An artifact of another target is that target's, and a dependency.
      if (!by_artifact.count(tr.real(g))) lines += "given " + tr.file_line(g);
    }
    own[kv.first] = traced ? sha256_hex(lines) : "";
  }

  // What every test reads: the toolchain, the kalku, the options and the
  // environment, and every file of the project that no compile reads.
  std::string common = std::string("kalku-cpp ") + VERSION + "\n";
  struct utsname u;
  if (uname(&u) == 0) {
    common += std::string(u.sysname) + " " + u.release + " " + u.version + " " + u.machine + "\n";
  }
  common += output_of({"cmake", "--version"}, build_, env_);
  common += generator + "\n";
  auto chains = parse_json(slurp(reply + "/" + text_of(chains_ref->get("jsonFile"))));
  if (!chains || !chains->get("toolchains")) return give_up("the file API did not describe the toolchains");
  for (const auto& c : chains->get("toolchains")->items) {
    std::string entry;
    canonical(c, entry);
    common += tr.named(entry) + "\n";
    std::string compiler = c.get("compiler") ? text_of(c.get("compiler")->get("path")) : "";
    if (!compiler.empty()) common += output_of({compiler, "--version"}, build_, env_);
  }
  for (const auto& a : cmake_args_) common += "arg " + a + "\n";
  for (const auto& kv : env_) common += "env " + kv + "\n";
  std::vector<std::string> unbuilt;
  std::vector<std::string> pending = {work_};
  while (!pending.empty()) {
    std::string dir = pending.back();
    pending.pop_back();
    for (const auto& name : names_in(dir)) {
      std::string path = dir + "/" + name;
      struct stat st;
      if (lstat(path.c_str(), &st) != 0) continue;
      if (S_ISDIR(st.st_mode)) {
        if (name != KALKU_OWN) pending.push_back(path);
      } else if (!read_by_compiles.count(path)) {
        unbuilt.push_back(path);
      }
    }
  }
  std::sort(unbuilt.begin(), unbuilt.end());
  for (const auto& f : unbuilt) common += "unbuilt " + tr.file_line(f);
  common = sha256_hex(common);

  // Each CTest test's fingerprint, by its number.
  std::map<int, std::string> by_number;
  for (const auto& test : tests_) {
    if (by_number.count(test.number)) continue;
    std::string& out = by_number[test.number];
    if (test.number < 1 || static_cast<std::size_t>(test.number) > ctest_entries_.size()) continue;
    const Json& entry = ctest_entries_[static_cast<std::size_t>(test.number - 1)];
    const Json* command = entry.get("command");
    if (!command || command->items.empty()) continue;
    auto exe = by_artifact.find(tr.real(text_of(&command->items[0])));
    if (exe == by_artifact.end()) continue;
    // What fixtures leave behind for a test is no part of its own build.
    if (has_property(entry, "FIXTURES_REQUIRED")) continue;
    std::string wd = tr.real(property(entry, "WORKING_DIRECTORY"));
    if (wd.empty() || !(within(wd, work_) || within(wd, build_))) continue;

    std::vector<std::string> wanted = {exe->second};
    std::string lines = common + "\n";
    std::string described;
    Json kept = entry;
    kept.fields.erase(
        std::remove_if(kept.fields.begin(), kept.fields.end(), [](const auto& f) { return f.first == "backtrace"; }),
        kept.fields.end());
    canonical(kept, described);
    lines += "test " + tr.named(described) + "\n";
    lines += "directory " + tr.named(wd) + " " + tr.tree(wd, artifacts) + "\n";
    // A path the test is given: another target's artifact is built for it,
    // and anything else is read.
    std::vector<std::string> said;
    strings_of(entry, said);
    for (const auto& s : said) {
      for (const std::string& top : {work_, build_}) {
        for (const auto& p : paths_in(s, top)) {
          std::string real = tr.real(p);
          auto it = by_artifact.find(real);
          if (it != by_artifact.end()) {
            wanted.push_back(it->second);
          } else if (real != wd) {
            lines += "given " + tr.named(p) + " " + tr.tree(real, artifacts) + "\n";
          }
        }
      }
    }
    // A module is only ever loaded by path, so one in the test's working
    // directory is taken to be loaded by it.
    for (const auto& kv : targets) {
      if (kv.second.type != "MODULE_LIBRARY") continue;
      for (const auto& a : kv.second.artifacts) {
        if (within(a, wd)) wanted.push_back(kv.first);
      }
    }
    std::set<std::string> closure;
    bool traced = true;
    while (!wanted.empty()) {
      std::string id = wanted.back();
      wanted.pop_back();
      if (!closure.insert(id).second) continue;
      // A dependency the file API does not describe is an imported or an
      // interface library: what it adds to a link is in the link command,
      // and what it adds to a compile is in the compile's inputs.
      auto t = targets.find(id);
      if (t == targets.end()) continue;
      if (own[id].empty()) {
        traced = false;
        break;
      }
      for (const auto& d : t->second.dependencies) wanted.push_back(d);
      // An artifact a target's description names, such as a library it
      // loads by a path compiled into it.
      for (const auto& p : paths_in(t->second.text, "<build>")) {
        auto it = by_artifact.find(tr.real(build_ + p.substr(7)));
        if (it != by_artifact.end()) wanted.push_back(it->second);
      }
    }
    if (!traced) continue;
    for (const auto& id : closure) {
      if (targets.count(id)) lines += "target " + id + " " + own[id] + "\n";
    }
    out = sha256_hex(lines).substr(0, 32);
  }

  std::vector<std::string> prints;
  for (const auto& test : tests_) {
    const std::string& p = by_number[test.number];
    if (p.empty() || test.suite < 0) {
      prints.push_back(p);
    } else {
      prints.push_back(sha256_hex(p + "\ncase " + test.test_case).substr(0, 32));
    }
  }
  return prints;
}

}  // namespace kalku
