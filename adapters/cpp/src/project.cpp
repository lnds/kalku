#include "project.hpp"

#include <dirent.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <climits>
#include <cstdlib>
#include <ctime>
#include <fstream>
#include <sstream>
#include <thread>

#include "json.hpp"

namespace kalku {

namespace {

// What the commands the kalku runs may see of its own environment: what
// finds the toolchain and what the toolchain is told to use.
const char* const INHERITED[] = {"PATH",
                                 "HOME",
                                 "USER",
                                 "LOGNAME",
                                 "TMPDIR",
                                 "LANG",
                                 "LC_ALL",
                                 "LC_CTYPE",
                                 "CC",
                                 "CXX",
                                 "CFLAGS",
                                 "CXXFLAGS",
                                 "CPPFLAGS",
                                 "LDFLAGS",
                                 "CPATH",
                                 "LIBRARY_PATH",
                                 "LD_LIBRARY_PATH",
                                 "PKG_CONFIG_PATH",
                                 "CMAKE_PREFIX_PATH",
                                 "CMAKE_GENERATOR",
                                 "CMAKE_MAKE_PROGRAM",
                                 "SDKROOT",
                                 "DEVELOPER_DIR",
                                 "MACOSX_DEPLOYMENT_TARGET",
                                 "VCPKG_ROOT",
                                 "CONAN_HOME",
                                 "CCACHE_DIR"};

std::string read_file(const std::string& path, bool* ok = nullptr) {
  std::ifstream in(path, std::ios::binary);
  if (ok) *ok = static_cast<bool>(in);
  std::ostringstream s;
  s << in.rdbuf();
  return s.str();
}

bool write_file(const std::string& path, const std::string& text, mode_t mode = 0644) {
  std::string tmp = path + ".kalku-tmp";
  {
    std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
    if (!out) return false;
    out << text;
    if (!out) return false;
  }
  chmod(tmp.c_str(), mode);
  return rename(tmp.c_str(), path.c_str()) == 0;
}

bool make_dirs(const std::string& path) {
  std::string at;
  std::stringstream parts(path);
  std::string part;
  if (!path.empty() && path[0] == '/') at = "/";
  while (std::getline(parts, part, '/')) {
    if (part.empty()) continue;
    at += part + "/";
    if (mkdir(at.c_str(), 0755) != 0 && errno != EEXIST) return false;
  }
  return true;
}

bool remove_all(const std::string& path) {
  struct stat st;
  if (lstat(path.c_str(), &st) != 0) return errno == ENOENT;
  if (S_ISDIR(st.st_mode)) {
    DIR* d = opendir(path.c_str());
    if (d) {
      while (dirent* e = readdir(d)) {
        std::string name = e->d_name;
        if (name == "." || name == "..") continue;
        remove_all(path + "/" + name);
      }
      closedir(d);
    }
    return rmdir(path.c_str()) == 0;
  }
  return unlink(path.c_str()) == 0;
}

std::vector<std::string> entries(const std::string& dir) {
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

bool exists(const std::string& path) {
  struct stat st;
  return stat(path.c_str(), &st) == 0;
}

// A directory CMake built into: it holds no source, and copying it would
// copy gigabytes of somebody else's objects.
bool build_tree(const std::string& dir) { return exists(dir + "/CMakeCache.txt"); }

bool sync_tree(const std::string& from, const std::string& to, const std::string& reni, bool top, std::string* why) {
  if (!make_dirs(to)) {
    *why = "cannot make " + to;
    return false;
  }
  std::vector<std::string> wanted;
  for (const auto& name : entries(from)) {
    std::string src = from + "/" + name;
    std::string dst = to + "/" + name;
    if (name == ".git" || src == reni) continue;
    if (top && (name == "CMakeCache.txt" || name == "CMakeFiles")) continue;
    struct stat st;
    if (lstat(src.c_str(), &st) != 0) continue;
    if (S_ISLNK(st.st_mode)) {
      char target[PATH_MAX];
      ssize_t n = readlink(src.c_str(), target, sizeof target - 1);
      if (n < 0) continue;
      target[n] = '\0';
      char old[PATH_MAX];
      ssize_t m = readlink(dst.c_str(), old, sizeof old - 1);
      if (m < 0 || std::string(old, static_cast<std::size_t>(m)) != target) {
        remove_all(dst);
        if (symlink(target, dst.c_str()) != 0) {
          *why = "cannot link " + dst;
          return false;
        }
      }
    } else if (S_ISDIR(st.st_mode)) {
      if (build_tree(src)) continue;
      struct stat dt;
      if (lstat(dst.c_str(), &dt) == 0 && !S_ISDIR(dt.st_mode)) remove_all(dst);
      if (!sync_tree(src, dst, reni, false, why)) return false;
    } else if (S_ISREG(st.st_mode)) {
      bool ok = false;
      std::string data = read_file(src, &ok);
      if (!ok) continue;
      bool same = false;
      std::string old = read_file(dst, &same);
      if (same && old == data) {
        wanted.push_back(name);
        continue;
      }
      struct stat dt;
      if (lstat(dst.c_str(), &dt) == 0 && !S_ISREG(dt.st_mode)) remove_all(dst);
      if (!write_file(dst, data, st.st_mode & 07777)) {
        *why = "cannot write " + dst;
        return false;
      }
    } else {
      continue;
    }
    wanted.push_back(name);
  }
  for (const auto& name : entries(to)) {
    if (std::find(wanted.begin(), wanted.end(), name) == wanted.end()) remove_all(to + "/" + name);
  }
  return true;
}

std::string resolved(const std::string& path) {
  char buf[PATH_MAX];
  return realpath(path.c_str(), buf) ? std::string(buf) : path;
}

bool ends_with(const std::string& s, const std::string& tail) {
  return s.size() >= tail.size() && s.compare(s.size() - tail.size(), tail.size(), tail) == 0;
}

std::string lower(std::string s) {
  for (auto& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  return s;
}

std::string base(const std::string& path) {
  auto slash = path.find_last_of('/');
  return slash == std::string::npos ? path : path.substr(slash + 1);
}

std::string dir_of(const std::string& path) {
  auto slash = path.find_last_of('/');
  return slash == std::string::npos ? "" : path.substr(0, slash);
}

// A shell command line split into its words, as a POSIX shell would.
std::vector<std::string> words(const std::string& line) {
  std::vector<std::string> out;
  std::string cur;
  bool in_word = false;
  char quote = 0;
  for (std::size_t i = 0; i < line.size(); i++) {
    char c = line[i];
    if (quote) {
      if (c == quote) {
        quote = 0;
      } else if (c == '\\' && quote == '"' && i + 1 < line.size()) {
        cur += line[++i];
      } else {
        cur += c;
      }
      continue;
    }
    if (c == '\'' || c == '"') {
      quote = c;
      in_word = true;
    } else if (c == '\\' && i + 1 < line.size()) {
      cur += line[++i];
      in_word = true;
    } else if (c == ' ' || c == '\t' || c == '\n') {
      if (in_word) out.push_back(cur);
      cur.clear();
      in_word = false;
    } else {
      cur += c;
      in_word = true;
    }
  }
  if (in_word) out.push_back(cur);
  return out;
}

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

// The value of an attribute in the text of one XML element.
std::string attribute(const std::string& element, const std::string& name) {
  std::string key = " " + name + "=\"";
  auto at = element.find(key);
  if (at == std::string::npos) return "";
  at += key.size();
  auto end = element.find('"', at);
  return end == std::string::npos ? "" : decode_xml(element.substr(at, end - at));
}

// What a test printed, as the report keeps it.
std::string system_out(const std::string& body) {
  auto open = body.find("<system-out>");
  if (open == std::string::npos) return "";
  open += 12;
  auto close = body.find("</system-out>", open);
  std::string text = body.substr(open, close == std::string::npos ? std::string::npos : close - open);
  if (text.rfind("<![CDATA[", 0) == 0) {
    text = text.substr(9);
    auto end = text.rfind("]]>");
    if (end != std::string::npos) text = text.substr(0, end);
    return text;
  }
  return decode_xml(text);
}

std::string last_lines(const std::string& text, std::size_t n) {
  std::vector<std::string> kept;
  std::stringstream in(text);
  std::string line;
  while (std::getline(in, line)) {
    while (!line.empty() && (line.back() == '\r' || line.back() == ' ')) line.pop_back();
    if (line.empty()) continue;
    kept.push_back(line);
    if (kept.size() > n) kept.erase(kept.begin());
  }
  std::string out;
  for (const auto& l : kept) out += (out.empty() ? "" : "\n") + l;
  return out;
}

// What CTest calls a failure that is not a test's own verdict: a signal, a
// timeout, an exception from the runtime.
bool test_said_it(const std::string& message) {
  return message == "Failed" || message.rfind("Required regular expression", 0) == 0 ||
         message.rfind("Regular expression", 0) == 0 || message.rfind("Fail Regular", 0) == 0 ||
         message.rfind("Error regular expression", 0) == 0;
}

}  // namespace

bool is_header(const std::string& file) {
  std::string f = lower(file);
  for (const char* e : {".h", ".hh", ".hpp", ".hxx", ".h++", ".ipp", ".tpp", ".inl"}) {
    if (ends_with(f, e)) return true;
  }
  return false;
}

bool is_c_file(const std::string& file) { return ends_with(file, ".c"); }

bool is_source_file(const std::string& file) {
  if (is_header(file) || is_c_file(file)) return true;
  for (const char* e : {".cpp", ".cc", ".cxx", ".c++", ".cp", ".C"}) {
    if (ends_with(file, e)) return true;
  }
  return false;
}

bool is_test_file(const std::string& file) {
  std::string path = "/" + file;
  for (const char* d : {"/test/", "/tests/", "/unittest/", "/unittests/", "/testing/"}) {
    if (path.find(d) != std::string::npos) return true;
  }
  std::string name = base(file);
  std::string stem = name.substr(0, name.find('.'));
  std::string l = lower(stem);
  return l == "test" || l == "tests" || l.rfind("test_", 0) == 0 || ends_with(l, "_test") || ends_with(l, "_tests") || ends_with(l, "_unittest") ||
         ends_with(stem, "Test") || ends_with(stem, "Tests") || ends_with(l, ".test") || ends_with(l, "-test");
}

Project::Project(const Hello& h, std::vector<std::string> cmake_args)
    : root_(h.root), reni_(h.reni), cmake_args_(std::move(cmake_args)) {
  std::string worker = std::to_string(h.worker);
  work_ = reni_ + "/work/" + worker;
  build_ = reni_ + "/build/" + worker;
  junit_ = reni_ + "/junit-" + worker + ".xml";
  for (const char* k : INHERITED) {
    if (const char* v = std::getenv(k)) env_.push_back(std::string(k) + "=" + v);
  }
  for (const auto& kv : h.env) env_.push_back(kv.first + "=" + kv.second);
}

bool Project::copy(std::string* why) {
  if (!sync_tree(root_, work_, reni_, true, why)) return false;
  work_ = resolved(work_);
  make_dirs(build_);
  build_ = resolved(build_);
  return true;
}

bool Project::copy_files(const std::vector<std::string>& files, std::string* why) {
  for (const auto& f : files) {
    std::string from = root_ + "/" + f, to = work_ + "/" + f;
    bool ok = false;
    std::string data = read_file(from, &ok);
    if (!ok) {
      remove_all(to);
      continue;
    }
    make_dirs(dir_of(to));
    if (!write_file(to, data)) {
      *why = "cannot write " + to;
      return false;
    }
  }
  return true;
}

bool Project::configured() const { return exists(build_ + "/CMakeCache.txt"); }

// Ninja when the machine has it and nobody chose a generator: it dates
// what it builds to the nanosecond and rebuilds one file quickly, which is
// what every cast asks of it.
Ran Project::configure(const std::atomic<bool>* cancel) {
  std::vector<std::string> argv = {
      "cmake", "-S", work_, "-B", build_, "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON", "-DBUILD_TESTING=ON"};
  bool chosen = false;
  for (const auto& a : cmake_args_) chosen = chosen || a.rfind("-G", 0) == 0;
  for (const auto& kv : env_) chosen = chosen || kv.rfind("CMAKE_GENERATOR=", 0) == 0;
  if (!chosen && on_path("ninja", env_)) {
    argv.emplace_back("-G");
    argv.emplace_back("Ninja");
  }
  for (const auto& a : cmake_args_) argv.push_back(a);
  Ran r = run(argv, work_, env_, cancel);
  if (!r.ok() && !r.cancelled && r.output.find("Does not match the generator used previously") != std::string::npos) {
    // A build tree of another generator is started over.
    remove_all(build_ + "/CMakeCache.txt");
    remove_all(build_ + "/CMakeFiles");
    r = run(argv, work_, env_, cancel);
  }
  return r;
}

// Make dates what it builds to the second: an object built in the second
// the library was last archived in is taken for older than it, and the
// library is not archived again. So a build under make starts in a second
// after the last one ended.
Ran Project::build(const std::atomic<bool>* cancel) {
  if (!precise() && last_build_ > 0) {
    while (static_cast<long>(std::time(nullptr)) <= last_build_) {
      if (cancel && cancel->load()) break;
      usleep(20 * 1000);
    }
  }
  unsigned n = std::max(1u, std::thread::hardware_concurrency());
  Ran r = run({"cmake", "--build", build_, "--parallel", std::to_string(n)}, work_, env_, cancel);
  last_build_ = static_cast<long>(std::time(nullptr));
  return r;
}

bool Project::precise() const {
  bool ok = false;
  std::string cache = read_file(build_ + "/CMakeCache.txt", &ok);
  return ok && cache.find("CMAKE_GENERATOR:INTERNAL=Ninja") != std::string::npos;
}

bool Project::list_tests(std::string* why) {
  Ran r = run({"ctest", "--test-dir", build_, "--show-only=json-v1"}, build_, env_);
  if (!r.ok()) {
    *why = "`ctest --show-only=json-v1` failed: " + named(r.output);
    return false;
  }
  // CTest prints its listing alone on stdout; what came before it on the
  // shared pipe is not part of it.
  auto start = r.output.find('{');
  std::string err;
  auto doc = start == std::string::npos ? std::nullopt : parse_json(r.output.substr(start), &err);
  if (!doc) {
    *why = "CTest's list of tests is not JSON: " + err;
    return false;
  }
  std::vector<std::string> files;
  std::vector<std::pair<int, int>> nodes;
  if (const Json* graph = doc->get("backtraceGraph")) {
    if (const Json* fs = graph->get("files")) {
      for (const auto& f : fs->items) files.push_back(f.text);
    }
    if (const Json* ns = graph->get("nodes")) {
      for (const auto& n : ns->items) {
        const Json* file = n.get("file");
        const Json* line = n.get("line");
        nodes.emplace_back(file ? static_cast<int>(file->integer) : -1, line ? static_cast<int>(line->integer) : 0);
      }
    }
  }
  tests_.clear();
  by_id_.clear();
  std::map<std::string, int> names;
  const Json* list = doc->get("tests");
  if (!list) return true;
  int number = 0;
  for (const auto& t : list->items) {
    number++;
    const Json* name = t.get("name");
    if (!name) continue;
    bool disabled = false;
    if (const Json* props = t.get("properties")) {
      for (const auto& p : props->items) {
        const Json* pn = p.get("name");
        const Json* pv = p.get("value");
        if (pn && pn->text == "DISABLED" && pv && pv->is_bool() && pv->boolean) disabled = true;
      }
    }
    if (disabled) continue;
    Test test;
    test.name = name->text;
    test.number = number;
    std::string file = "CMakeLists.txt";
    int line = 0;
    if (const Json* bt = t.get("backtrace");
        bt && bt->is_integer() && bt->integer >= 0 && static_cast<std::size_t>(bt->integer) < nodes.size()) {
      auto node = nodes[static_cast<std::size_t>(bt->integer)];
      if (node.first >= 0 && static_cast<std::size_t>(node.first) < files.size()) {
        std::string path = files[static_cast<std::size_t>(node.first)];
        if (path.rfind(work_ + "/", 0) == 0) file = path.substr(work_.size() + 1);
        line = node.second;
      }
    }
    test.file = file;
    test.id = test.name;
    if (names[test.name]++ > 0) test.id = test.name + " (" + file + ":" + std::to_string(line) + ")";
    by_id_[test.id] = tests_.size();
    tests_.push_back(test);
  }
  return true;
}

const Test* Project::lookup(const std::string& id) const {
  auto it = by_id_.find(id);
  return it == by_id_.end() ? nullptr : &tests_[it->second];
}

TestRun Project::run_tests(const std::vector<const Test*>& tests, const std::atomic<bool>* cancel) {
  TestRun out;
  if (tests.empty()) return out;
  std::string numbers = "0,0,0";
  for (const auto* t : tests) numbers += "," + std::to_string(t->number);
  unlink(junit_.c_str());
  Ran r = run({"ctest", "--test-dir", build_, "-I", numbers, "--output-junit", junit_, "--output-on-failure"}, build_,
              env_, cancel);
  if (r.cancelled) {
    out.cancelled = true;
    return out;
  }
  bool ok = false;
  std::string xml = read_file(junit_, &ok);
  if (!ok || xml.empty()) {
    out.broken = r.unstarted ? "ctest could not be started" : "ctest wrote no report: " + named(r.output);
    return out;
  }
  std::size_t at = 0;
  while ((at = xml.find("<testcase ", at)) != std::string::npos) {
    std::size_t open_end = xml.find('>', at);
    if (open_end == std::string::npos) break;
    std::string element = xml.substr(at, open_end - at);
    bool empty = open_end > 0 && xml[open_end - 1] == '/';
    std::size_t close = empty ? open_end : xml.find("</testcase>", open_end);
    std::string body = close == std::string::npos ? "" : xml.substr(open_end, close - open_end);
    TestResult res;
    res.duration_ms = static_cast<long>(std::strtod(attribute(element, "time").c_str(), nullptr) * 1000 + 0.5);
    std::string status = attribute(element, "status");
    std::string failure;
    if (auto f = body.find("<failure"); f != std::string::npos) {
      failure = attribute(body.substr(f, body.find('>', f) - f), "message");
    }
    std::string said = last_lines(system_out(body), 10);
    if (status == "run") {
      res.status = TestResult::Status::Passed;
    } else if (status == "fail") {
      if (test_said_it(failure)) {
        res.status = TestResult::Status::Failed;
        res.message = said.empty() ? "it failed" : "it failed: " + named(said);
      } else {
        res.status = TestResult::Status::Crashed;
        res.message = failure.empty() ? "it ended without a verdict" : "it ended with " + failure;
      }
    } else {
      res.status = TestResult::Status::NotRun;
      res.message = "it did not run";
    }
    out.by_name[attribute(element, "name")] = res;
    at = open_end;
  }
  return out;
}

std::optional<std::string> Project::read(const std::string& file) const {
  bool ok = false;
  std::string text = read_file(work_ + "/" + file, &ok);
  if (!ok) return std::nullopt;
  return text;
}

// A file written for a cast, or put back after one, is dated after every
// build so far: make compares whole seconds, and a wekufe written in the
// second its last object was built would otherwise be taken for built.
bool Project::write(const std::string& file, const std::string& text) {
  std::string path = work_ + "/" + file;
  struct stat st;
  mode_t mode = stat(path.c_str(), &st) == 0 ? (st.st_mode & 07777) : 0644;
  if (!write_file(path, text, mode)) return false;
  long now = static_cast<long>(std::time(nullptr));
  stamp_ = std::max(now + 1, stamp_ + 1);
  struct timeval times[2];
  times[0].tv_sec = times[1].tv_sec = static_cast<time_t>(stamp_);
  times[0].tv_usec = times[1].tv_usec = 0;
  return utimes(path.c_str(), times) == 0;
}

std::optional<Compile> Project::compile_of(const std::string& file) const {
  bool ok = false;
  std::string text = read_file(build_ + "/compile_commands.json", &ok);
  if (!ok) return std::nullopt;
  auto doc = parse_json(text);
  if (!doc || !doc->is_array()) return std::nullopt;
  std::string want = work_ + "/" + file;
  std::string dir = dir_of(want);
  // The file's own command; else one beside it; else one of the project's
  // own code; else, for a library of headers alone, one of its tests.
  const Json* exact = nullptr;
  const Json* beside = nullptr;
  const Json* own = nullptr;
  const Json* any = nullptr;
  for (const auto& e : doc->items) {
    const Json* f = e.get("file");
    const Json* d = e.get("directory");
    if (!f || !d) continue;
    std::string path = f->text;
    if (!path.empty() && path[0] != '/') path = d->text + "/" + path;
    path = resolved(path);
    std::string rel = path.rfind(work_ + "/", 0) == 0 ? path.substr(work_.size() + 1) : "";
    if (path == want) exact = &e;
    if (!beside && dir_of(path) == dir) beside = &e;
    if (!own && !rel.empty() && !is_test_file(rel)) own = &e;
    if (!any && !rel.empty()) any = &e;
  }
  const Json* chosen = exact ? exact : beside ? beside : own ? own : is_header(file) ? any : nullptr;
  if (!chosen) return std::nullopt;
  Compile c;
  c.directory = chosen->get("directory")->text;
  std::vector<std::string> args;
  if (const Json* a = chosen->get("arguments")) {
    for (const auto& x : a->items) args.push_back(x.text);
  } else if (const Json* cmd = chosen->get("command")) {
    args = words(cmd->text);
  }
  std::string compiled = chosen->get("file")->text;
  std::string compiled_abs = compiled.empty() || compiled[0] == '/' ? compiled : c.directory + "/" + compiled;
  c.c = is_c_file(compiled);
  for (std::size_t i = 0; i + 1 < args.size(); i++) {
    if (args[i] == "-x") c.c = args[i + 1] == "c" || args[i + 1] == "c-header";
  }
  for (std::size_t i = 0; i < args.size(); i++) {
    if (i > 0 && (args[i] == compiled || args[i] == compiled_abs || resolved(args[i]) == resolved(compiled_abs))) {
      continue;
    }
    c.args.push_back(args[i]);
  }
  return c;
}

int Project::translation_units() const {
  bool ok = false;
  std::string text = read_file(build_ + "/compile_commands.json", &ok);
  auto doc = ok ? parse_json(text) : std::nullopt;
  return doc && doc->is_array() ? static_cast<int>(doc->items.size()) : 0;
}

std::string Project::named(const std::string& message) const {
  std::string out = message;
  for (const std::string& from : {work_ + "/", build_ + "/"}) {
    std::size_t at = 0;
    while ((at = out.find(from, at)) != std::string::npos) out.erase(at, from.size());
  }
  return out;
}

std::vector<std::string> Project::differences() const {
  std::string passed;
  for (const auto& kv : env_) passed += (passed.empty() ? "" : ", ") + kv.substr(0, kv.find('='));
  return {
      "it ran in a copy of the project kept in the reni, " + work_ +
          ": what the copy leaves out is not there, `.git` and any directory CMake built into",
      "it was configured afresh into a build tree of its own, " + build_ +
          ", with BUILD_TESTING=ON and the project's own defaults: a cache you configured with other options is "
          "not used",
      "the tests ran through `ctest`, one at a time, from that build tree",
      "the tests see none of your environment but " + passed +
          ": any other variable set for them in your shell is not there",
  };
}

}  // namespace kalku
