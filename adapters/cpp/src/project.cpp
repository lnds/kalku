#include "project.hpp"

#include <dirent.h>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <climits>
#include <cstdlib>
#include <ctime>
#include <fstream>
#include <iostream>
#include <sstream>
#include <thread>

#include "cases.hpp"
#include "json.hpp"
#include "version.hpp"

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

// Makes `to` hold what `from` does, writing only what differs; `changed`
// says whether anything did.
bool sync_tree(const std::string& from, const std::string& to, const std::string& reni, bool top, std::string* why,
               bool* changed) {
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
        *changed = true;
        if (symlink(target, dst.c_str()) != 0) {
          *why = "cannot link " + dst;
          return false;
        }
      }
    } else if (S_ISDIR(st.st_mode)) {
      if (build_tree(src)) continue;
      struct stat dt;
      if (lstat(dst.c_str(), &dt) == 0 && !S_ISDIR(dt.st_mode)) remove_all(dst);
      if (!sync_tree(src, dst, reni, false, why, changed)) return false;
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
      *changed = true;
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
    if (std::find(wanted.begin(), wanted.end(), name) == wanted.end()) {
      remove_all(to + "/" + name);
      *changed = true;
    }
  }
  return true;
}

struct timespec mtime_of(const struct stat& st) {
#ifdef __APPLE__
  return st.st_mtimespec;
#else
  return st.st_mtim;
#endif
}

bool date(const std::string& path, const struct timespec& t) {
  struct timespec times[2] = {t, t};
  return utimensat(AT_FDCWD, path.c_str(), times, 0) == 0;
}

using Rewrites = std::vector<std::pair<std::string, std::string>>;

// Text says the worker's paths where it said the base's. What holds a NUL
// byte is an object, a library or an executable, and is left as it is: its
// paths are only for a debugger, and make never reads them.
std::string rebase(std::string data, const Rewrites& rw) {
  if (data.find('\0') != std::string::npos) return data;
  for (const auto& r : rw) {
    std::size_t at = 0;
    while ((at = data.find(r.first, at)) != std::string::npos) {
      data.replace(at, r.first.size(), r.second);
      at += r.second.size();
    }
  }
  return data;
}

// Makes `to` the tree `from` is, each file dated as it is there, so that
// make finds in the copy what it found built in the original.
bool clone_tree(const std::string& from, const std::string& to, const Rewrites& rw, std::string* why) {
  if (!make_dirs(to)) {
    *why = "cannot make " + to;
    return false;
  }
  std::vector<std::string> wanted;
  for (const auto& name : entries(from)) {
    std::string src = from + "/" + name;
    std::string dst = to + "/" + name;
    struct stat st, dt;
    if (lstat(src.c_str(), &st) != 0) continue;
    bool there = lstat(dst.c_str(), &dt) == 0;
    if (S_ISLNK(st.st_mode)) {
      char target[PATH_MAX];
      ssize_t n = readlink(src.c_str(), target, sizeof target - 1);
      if (n < 0) continue;
      std::string want = rebase(std::string(target, static_cast<std::size_t>(n)), rw);
      char old[PATH_MAX];
      ssize_t m = there ? readlink(dst.c_str(), old, sizeof old - 1) : -1;
      if (m < 0 || std::string(old, static_cast<std::size_t>(m)) != want) {
        remove_all(dst);
        if (symlink(want.c_str(), dst.c_str()) != 0) {
          *why = "cannot link " + dst;
          return false;
        }
      }
    } else if (S_ISDIR(st.st_mode)) {
      if (there && !S_ISDIR(dt.st_mode)) remove_all(dst);
      if (!clone_tree(src, dst, rw, why)) return false;
    } else if (S_ISREG(st.st_mode)) {
      bool ok = false;
      std::string data = read_file(src, &ok);
      if (!ok) {
        *why = "cannot read " + src;
        return false;
      }
      data = rebase(std::move(data), rw);
      bool same = there && S_ISREG(dt.st_mode) && static_cast<std::size_t>(dt.st_size) == data.size() &&
                  (dt.st_mode & 07777) == (st.st_mode & 07777) && read_file(dst) == data;
      if (!same) {
        if (there && !S_ISREG(dt.st_mode)) remove_all(dst);
        if (!write_file(dst, data, st.st_mode & 07777)) {
          *why = "cannot write " + dst;
          return false;
        }
      }
      if (!date(dst, mtime_of(st))) {
        *why = "cannot date " + dst;
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

std::string generator_of(const std::string& build) {
  bool ok = false;
  std::string cache = read_file(build + "/CMakeCache.txt", &ok);
  const std::string key = "CMAKE_GENERATOR:INTERNAL=";
  auto at = ok ? cache.find(key) : std::string::npos;
  if (at == std::string::npos) return "";
  at += key.size();
  return cache.substr(at, cache.find('\n', at) - at);
}

// Held from open to close; closing the file ends it, and so does the end of
// the process, however it ends.
class Lock {
 public:
  explicit Lock(const std::string& path) : fd_(open(path.c_str(), O_RDWR | O_CREAT, 0644)) {}
  ~Lock() {
    if (fd_ >= 0) close(fd_);
  }
  Lock(const Lock&) = delete;
  Lock& operator=(const Lock&) = delete;
  bool take(bool exclusive) { return fd_ >= 0 && flock(fd_, exclusive ? LOCK_EX : LOCK_SH) == 0; }

 private:
  int fd_;
};

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

// A command that is given `seconds` to answer, and ended when it does not.
Ran within(const std::vector<std::string>& argv, const std::string& dir, const std::vector<std::string>& env,
           int seconds) {
  std::atomic<bool> late{false};
  std::atomic<bool> done{false};
  std::thread watch([&] {
    auto until = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
    while (!done.load() && std::chrono::steady_clock::now() < until) usleep(20 * 1000);
    if (!done.load()) late = true;
  });
  Ran r = run(argv, dir, env, &late);
  done = true;
  watch.join();
  return r;
}

// The test properties that leave a test's verdict its exit code, and that
// a case the kalku runs itself can do without.
bool harmless(const std::string& property) {
  for (const char* p : {"LABELS", "COST", "PROCESSORS", "RUN_SERIAL", "RESOURCE_LOCK", "RESOURCE_GROUPS", "DEPENDS",
                        "TIMEOUT", "ATTACHED_FILES", "ATTACHED_FILES_ON_FAIL", "MEASUREMENT", "DISABLED"}) {
    if (property == p) return true;
  }
  return false;
}

std::string how_it_ended(const Ran& r) {
  if (r.signal) return "ended by signal " + std::to_string(r.signal);
  return "exited with " + std::to_string(r.exit_code);
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
  return l == "test" || l == "tests" || l.rfind("test_", 0) == 0 || ends_with(l, "_test") || ends_with(l, "_tests") ||
         ends_with(l, "_unittest") || ends_with(stem, "Test") || ends_with(stem, "Tests") || ends_with(l, ".test") ||
         ends_with(l, "-test");
}

Project::Project(const Hello& h, std::vector<std::string> cmake_args)
    : root_(h.root), reni_(h.reni), cmake_args_(std::move(cmake_args)) {
  worker_ = std::to_string(h.worker);
  work_ = reni_ + "/work/" + worker_;
  build_ = reni_ + "/build/" + worker_;
  base_work_ = reni_ + "/work/base";
  base_build_ = reni_ + "/build/base";
  junit_ = reni_ + "/junit-" + worker_ + ".xml";
  cases_xml_ = reni_ + "/cases-" + worker_ + ".xml";
  for (const char* k : INHERITED) {
    if (const char* v = std::getenv(k)) env_.push_back(std::string(k) + "=" + v);
  }
  for (const auto& kv : h.env) env_.push_back(kv.first + "=" + kv.second);
}

bool Project::copy(std::string* why) {
  bool changed = false;
  if (!sync_tree(root_, work_, reni_, true, why, &changed)) return false;
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

// The generator CMake is told to use, or empty for its own default.
std::string Project::generator() const {
  std::string chosen;
  for (std::size_t i = 0; i < cmake_args_.size(); i++) {
    const std::string& a = cmake_args_[i];
    if (a == "-G" && i + 1 < cmake_args_.size()) {
      chosen = cmake_args_[i + 1];
    } else if (a.rfind("-G", 0) == 0 && a.size() > 2) {
      chosen = a.substr(2);
    }
  }
  for (const auto& kv : env_) {
    if (chosen.empty() && kv.rfind("CMAKE_GENERATOR=", 0) == 0) chosen = kv.substr(16);
  }
  if (chosen.empty() && on_path("ninja", env_)) chosen = "Ninja";
  return chosen;
}

bool Project::shares_build() const {
  std::string g = generator();
  return g.empty() || g == "Unix Makefiles";
}

std::vector<std::pair<std::string, std::string>> Project::rebased() const {
  std::vector<std::pair<std::string, std::string>> rw = {{base_work_, work_}, {base_build_, build_}};
  // The reni as the kaikai side named it, where that is not its real path.
  std::string raw = reni_ + "/work/base";
  if (raw != base_work_) {
    rw.emplace_back(raw, reni_ + "/work/" + worker_);
    rw.emplace_back(reni_ + "/build/base", reni_ + "/build/" + worker_);
  }
  return rw;
}

// The base is brought up to date by one worker at a time, and copied by any
// number of them while none is changing it. It is built again only when the
// project or the options changed since it was last built whole.
BaseStage Project::from_base(Ran* ran, std::string* why) {
  make_dirs(reni_);
  Lock lock(reni_ + "/base.lock");
  if (!lock.take(true)) {
    *why = "cannot lock " + reni_ + "/base.lock";
    return BaseStage::Copy;
  }
  bool changed = false;
  if (!sync_tree(root_, base_work_, reni_, true, why, &changed)) return BaseStage::Copy;
  base_work_ = resolved(base_work_);
  make_dirs(base_build_);
  base_build_ = resolved(base_build_);
  std::string done = reni_ + "/base.done";
  std::string stamp = std::string(VERSION) + "\n";
  for (const auto& a : cmake_args_) stamp += a + "\n";
  for (const auto& kv : env_) stamp += kv + "\n";
  bool ok = false;
  bool current = !changed && read_file(done, &ok) == stamp && ok && exists(base_build_ + "/CMakeCache.txt");
  if (!current) {
    unlink(done.c_str());
    *ran = configure_in(base_work_, base_build_, nullptr);
    if (!ran->ok()) return BaseStage::Configure;
    if (generator_of(base_build_) != "Unix Makefiles") return BaseStage::Unshared;
    *ran = build_in(base_build_, nullptr);
    if (!ran->ok()) return BaseStage::Build;
    write_file(done, stamp);
  }
  if (!lock.take(false)) {
    *why = "cannot lock " + reni_ + "/base.lock";
    return BaseStage::Copy;
  }
  make_dirs(work_);
  work_ = resolved(work_);
  make_dirs(build_);
  build_ = resolved(build_);
  auto rw = rebased();
  if (!clone_tree(base_work_, work_, rw, why) || !clone_tree(base_build_, build_, rw, why)) return BaseStage::Copy;
  shared_ = true;
  return BaseStage::Done;
}

Ran Project::configure(const std::atomic<bool>* cancel) { return configure_in(work_, build_, cancel); }

// Ninja when the machine has it and nobody chose a generator: it dates
// what it builds to the nanosecond and rebuilds one file quickly, which is
// what every cast asks of it.
Ran Project::configure_in(const std::string& work, const std::string& build, const std::atomic<bool>* cancel) {
  std::vector<std::string> argv = {
      "cmake", "-S", work, "-B", build, "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON", "-DBUILD_TESTING=ON"};
  bool chosen = false;
  for (const auto& a : cmake_args_) chosen = chosen || a.rfind("-G", 0) == 0;
  for (const auto& kv : env_) chosen = chosen || kv.rfind("CMAKE_GENERATOR=", 0) == 0;
  if (!chosen && on_path("ninja", env_)) {
    argv.emplace_back("-G");
    argv.emplace_back("Ninja");
  }
  for (const auto& a : cmake_args_) argv.push_back(a);
  Ran r = run(argv, work, env_, cancel);
  if (!r.ok() && !r.cancelled && r.output.find("Does not match the generator used previously") != std::string::npos) {
    // A build tree of another generator is started over.
    remove_all(build + "/CMakeCache.txt");
    remove_all(build + "/CMakeFiles");
    r = run(argv, work, env_, cancel);
  }
  return r;
}

Ran Project::build(const std::atomic<bool>* cancel) { return build_in(build_, cancel); }

// Make dates what it builds to the second: an object built in the second
// the library was last archived in is taken for older than it, and the
// library is not archived again. So a build under make starts in a second
// after the last one ended.
Ran Project::build_in(const std::string& build, const std::atomic<bool>* cancel) {
  if (generator_of(build).rfind("Ninja", 0) != 0 && last_build_ > 0) {
    while (static_cast<long>(std::time(nullptr)) <= last_build_) {
      if (cancel && cancel->load()) break;
      usleep(20 * 1000);
    }
  }
  unsigned n = std::max(1u, std::thread::hardware_concurrency());
  Ran r = run({"cmake", "--build", build, "--parallel", std::to_string(n)}, build, env_, cancel);
  last_build_ = static_cast<long>(std::time(nullptr));
  return r;
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
  suites_.clear();
  listed_.clear();
  probed_.clear();
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
    std::string not_listed;
    std::vector<Test> cases = cases_of(test, t, &not_listed);
    if (cases.empty()) cases.push_back(test);
    if (!not_listed.empty()) {
      listed_.push_back("`" + test.name + "` ran whole, as one test: " + not_listed);
      std::cerr << "kalku-cpp: " << listed_.back() << "\n";
    }
    for (const auto& c : cases) {
      if (by_id_.count(c.id)) continue;
      by_id_[c.id] = tests_.size();
      tests_.push_back(c);
    }
  }
  return true;
}

std::vector<Test> Project::cases_of(const Test& test, const Json& entry, std::string* why) {
  // Only a test that runs one executable as it is: arguments are how a
  // project, or a framework's own discovery, chooses what a test runs.
  const Json* command = entry.get("command");
  if (!command || !command->is_array() || command->items.size() != 1) return {};
  std::string exe = resolved(command->items[0].text);
  struct stat st;
  if (exe.rfind(build_ + "/", 0) != 0 || stat(exe.c_str(), &st) != 0 || !S_ISREG(st.st_mode) ||
      access(exe.c_str(), X_OK) != 0) {
    return {};
  }
  std::string dir = build_;
  std::vector<std::string> env = env_;
  std::string unapplied;
  if (const Json* props = entry.get("properties")) {
    for (const auto& p : props->items) {
      const Json* pn = p.get("name");
      const Json* pv = p.get("value");
      if (!pn || !pv) continue;
      if (pn->text == "WORKING_DIRECTORY" && pv->is_string()) {
        dir = pv->text;
      } else if (pn->text == "ENVIRONMENT") {
        std::vector<std::string> set;
        if (pv->is_string()) set.push_back(pv->text);
        for (const auto& v : pv->items) set.push_back(v.text);
        for (const auto& kv : set) {
          if (kv.find('=') == std::string::npos) continue;
          std::string key = kv.substr(0, kv.find('=') + 1);
          env.erase(std::remove_if(env.begin(), env.end(), [&](const std::string& e) { return e.rfind(key, 0) == 0; }),
                    env.end());
          env.push_back(kv);
        }
      } else if (!harmless(pn->text)) {
        unapplied += (unapplied.empty() ? "" : ", ") + pn->text;
      }
    }
  }
  // What the executable is, asked of it: doctest answers `--version` with
  // its banner, and Catch2 refuses it and answers `--libidentify`. An
  // executable that is neither runs its own tests, so only one that refused
  // the first question is asked the second.
  auto known = probed_.find(exe);
  if (known == probed_.end()) {
    Framework f = Framework::None;
    Ran v = within({exe, "--version"}, dir, env, 10);
    if (says_doctest(v.output)) {
      f = Framework::Doctest;
    } else if (!v.cancelled && !v.unstarted && v.signal == 0 && v.exit_code != 0) {
      f = says_catch2(within({exe, "--libidentify"}, dir, env, 10).output);
    }
    known = probed_.emplace(exe, f).first;
  }
  Framework f = known->second;
  if (f == Framework::None) return {};
  std::string framework = framework_name(f);
  if (!unapplied.empty()) {
    *why = "it is a " + framework + " executable, and the kalku does not run its cases with what " + unapplied +
           " asks of a test";
    return {};
  }
  unlink(cases_xml_.c_str());
  std::vector<std::string> argv = {exe};
  for (const auto& a : listing_args(f, cases_xml_)) argv.push_back(a);
  Ran r = within(argv, dir, env, 30);
  if (r.cancelled || r.signal != 0 || r.unstarted) {
    *why = "it is a " + framework + " executable, and listing its cases " +
           (r.cancelled   ? std::string("took longer than 30 s")
            : r.unstarted ? "could not start"
                          : how_it_ended(r));
    return {};
  }
  std::string text = f == Framework::Catch2Legacy ? r.output : read_file(cases_xml_);
  std::vector<Listed> listed;
  std::string reason;
  if (!read_listing(f, text, &listed, &reason)) {
    *why = "it is a " + framework + " executable, and " + reason;
    return {};
  }
  std::sort(listed.begin(), listed.end(), [](const Listed& a, const Listed& b) { return a.name < b.name; });
  int index = static_cast<int>(suites_.size());
  suites_.push_back(Suite{test.name, f, exe, dir, env, listed.size()});
  std::vector<Test> out;
  for (const auto& l : listed) {
    Test c = test;
    c.test_case = l.name;
    c.suite = index;
    c.id = test.id + "/" + l.name;
    if (l.file.rfind(work_ + "/", 0) == 0) c.file = l.file.substr(work_.size() + 1);
    out.push_back(c);
  }
  listed_.push_back("the " + std::to_string(out.size()) + " " + framework + " cases of `" + test.name +
                    "` ran from its executable, chosen by name, and not through `ctest`");
  return out;
}

const Test* Project::lookup(const std::string& id) const {
  auto it = by_id_.find(id);
  return it == by_id_.end() ? nullptr : &tests_[it->second];
}

TestRun Project::run_tests(const std::vector<const Test*>& tests, const std::atomic<bool>* cancel) {
  TestRun out;
  std::vector<const Test*> whole;
  std::map<int, std::vector<const Test*>> by_suite;
  for (const auto* t : tests) (t->suite < 0 ? whole : by_suite[t->suite]).push_back(t);
  if (!whole.empty()) run_ctest(whole, cancel, out);
  for (const auto& kv : by_suite) {
    if (out.cancelled || !out.broken.empty()) break;
    run_cases(kv.first, kv.second, cancel, out);
  }
  return out;
}

void Project::run_cases(int index, const std::vector<const Test*>& tests, const std::atomic<bool>* cancel,
                        TestRun& out) {
  const Suite& s = suites_[static_cast<std::size_t>(index)];
  Ran ran;
  bool reported = false;
  auto once = [&](const std::vector<std::string>& names) {
    unlink(cases_xml_.c_str());
    std::vector<std::string> argv = {s.executable};
    for (const auto& a : run_args(s.framework, names, cases_xml_)) argv.push_back(a);
    ran = run(argv, s.directory, s.env, cancel);
    bool ok = false;
    std::string xml = read_file(cases_xml_, &ok);
    reported = ok && !xml.empty();
    return read_report(s.framework, xml);
  };
  // Every case asked for is the executable run whole, as CTest runs it.
  bool all = tests.size() >= s.cases;
  std::vector<std::string> names;
  if (!all) {
    for (const auto* t : tests) names.push_back(t->test_case);
  }
  auto got = once(names);
  if (!all && !ran.cancelled) {
    bool failed = false;
    for (const auto* t : tests) {
      auto it = got.find(t->test_case);
      failed = failed || (it != got.end() && it->second.status == CaseResult::Status::Failed);
    }
    // A case can fail when run apart from the cases the suite runs before
    // it, and that is no verdict on the wekufe: the run of the whole suite
    // is the one that judges.
    if (failed) got = once({});
  }
  if (ran.cancelled) {
    out.cancelled = true;
    return;
  }
  if (!reported) {
    out.broken = ran.unstarted ? "`" + s.name + "` could not be started"
                               : "`" + s.name + "` wrote no report: it " + how_it_ended(ran) + ": " +
                                     named(last_lines(ran.output, 10));
    return;
  }
  bool blamed = false;
  for (const auto* t : tests) {
    TestResult res;
    auto it = got.find(t->test_case);
    if (it == got.end()) {
      res.message = "it did not run";
    } else {
      res.duration_ms = it->second.duration_ms;
      res.message = named(it->second.message);
      switch (it->second.status) {
        case CaseResult::Status::Passed:
          res.status = TestResult::Status::Passed;
          break;
        case CaseResult::Status::Failed:
          res.status = TestResult::Status::Failed;
          blamed = true;
          break;
        case CaseResult::Status::Crashed:
          res.status = TestResult::Status::Crashed;
          blamed = true;
          break;
      }
    }
    out.by_id[t->id] = res;
  }
  // An executable that failed when no case did failed outside them, as a
  // static destructor can: that is no case's verdict, and no case passed.
  if (!ran.ok() && !blamed) {
    for (const auto* t : tests) {
      TestResult& res = out.by_id[t->id];
      if (res.status != TestResult::Status::Passed) continue;
      res.status = TestResult::Status::Crashed;
      res.message = "its cases passed, and `" + s.name + "` then " + how_it_ended(ran);
    }
  }
}

void Project::run_ctest(const std::vector<const Test*>& tests, const std::atomic<bool>* cancel, TestRun& out) {
  std::string numbers = "0,0,0";
  for (const auto* t : tests) numbers += "," + std::to_string(t->number);
  unlink(junit_.c_str());
  Ran r = run({"ctest", "--test-dir", build_, "-I", numbers, "--output-junit", junit_, "--output-on-failure"}, build_,
              env_, cancel);
  if (r.cancelled) {
    out.cancelled = true;
    return;
  }
  bool ok = false;
  std::string xml = read_file(junit_, &ok);
  if (!ok || xml.empty()) {
    out.broken = r.unstarted ? "ctest could not be started" : "ctest wrote no report: " + named(r.output);
    return;
  }
  std::map<std::string, TestResult> by_name;
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
    by_name[attribute(element, "name")] = res;
    at = open_end;
  }
  for (const auto* t : tests) {
    auto it = by_name.find(t->name);
    if (it != by_name.end()) out.by_id[t->id] = it->second;
  }
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
  for (const std::string& from : {work_ + "/", build_ + "/", base_work_ + "/", base_build_ + "/"}) {
    std::size_t at = 0;
    while ((at = out.find(from, at)) != std::string::npos) out.erase(at, from.size());
  }
  return out;
}

std::vector<std::string> Project::differences() const {
  std::string passed;
  for (const auto& kv : env_) passed += (passed.empty() ? "" : ", ") + kv.substr(0, kv.find('='));
  std::vector<std::string> out = {
      "it ran in a copy of the project kept in the reni, " + work_ +
          ": what the copy leaves out is not there, `.git` and any directory CMake built into",
      shared_ ? "it was configured afresh with BUILD_TESTING=ON and the project's own defaults, built once in " +
                    base_build_ + " and copied into a build tree of its own, " + build_ +
                    ": a cache you configured with other options is not used"
              : "it was configured afresh into a build tree of its own, " + build_ +
                    ", with BUILD_TESTING=ON and the project's own defaults: a cache you configured with other "
                    "options is not used",
      "the tests ran through `ctest`, one at a time, from that build tree",
      "the tests see none of your environment but " + passed +
          ": any other variable set for them in your shell is not there",
  };
  out.insert(out.end(), listed_.begin(), listed_.end());
  return out;
}

}  // namespace kalku
