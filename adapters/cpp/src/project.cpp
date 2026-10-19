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

bool ends_with(const std::string& s, const std::string& tail) {
  return s.size() >= tail.size() && s.compare(s.size() - tail.size(), tail.size(), tail) == 0;
}

// What a build tree's cache holds for `name`, whatever its type.
std::string cached(const std::string& build, const std::string& name) {
  std::stringstream lines(read_file(build + "/CMakeCache.txt"));
  std::string line;
  while (std::getline(lines, line)) {
    if (line.rfind(name + ":", 0) == 0 && line.find('=') != std::string::npos) return line.substr(line.find('=') + 1);
  }
  return "";
}

// An executable or a shared library, as its first bytes say: a Mach-O or
// an ELF file that a link wrote, and not an object a compiler did.
bool linked_image(const std::string& path) {
  std::ifstream in(path, std::ios::binary);
  unsigned char h[20] = {0};
  if (!in.read(reinterpret_cast<char*>(h), sizeof h)) return false;
  if (h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F') {
    unsigned type = h[5] == 2 ? (h[16] << 8 | h[17]) : (h[17] << 8 | h[16]);
    return type == 2 || type == 3;
  }
  if ((h[0] == 0xcf || h[0] == 0xce) && h[1] == 0xfa && h[2] == 0xed && h[3] == 0xfe) {
    unsigned type = h[12] | h[13] << 8;
    return type == 2 || type == 6 || type == 8;
  }
  // A universal binary counts its architectures where a Java class, with
  // the same magic, puts its version.
  return h[0] == 0xca && h[1] == 0xfe && h[2] == 0xba && h[3] == 0xbe && h[4] == 0 && h[5] == 0 && h[6] == 0 &&
         h[7] < 20;
}

// Removes from a copy of the base's build tree what the base linked and
// that names the base: a linked file can find the shared libraries it loads
// by the base's paths, and binary text cannot be rewritten to name others,
// so the copy links it again in its own tree.
void unlink_images(const std::string& dir, const Rewrites& rw) {
  for (const auto& name : entries(dir)) {
    std::string path = dir + "/" + name;
    struct stat st;
    if (lstat(path.c_str(), &st) != 0) continue;
    if (S_ISDIR(st.st_mode)) {
      unlink_images(path, rw);
    } else if (S_ISREG(st.st_mode) && linked_image(path)) {
      std::string data = read_file(path);
      for (const auto& r : rw) {
        if (data.find(r.first) == std::string::npos) continue;
        unlink(path.c_str());
        break;
      }
    }
  }
}

std::string generator_of(const std::string& build) { return cached(build, "CMAKE_GENERATOR"); }

bool shared_library(const std::string& name) {
  if (ends_with(name, ".dylib") || ends_with(name, ".so")) return true;
  auto at = name.find(".so.");
  return at != std::string::npos && name.find_first_not_of("0123456789.", at + 4) == std::string::npos;
}

void libraries_in(const std::string& dir, std::map<std::string, int>& seen) {
  for (const auto& name : entries(dir)) {
    std::string path = dir + "/" + name;
    struct stat st;
    if (lstat(path.c_str(), &st) != 0) continue;
    if (S_ISDIR(st.st_mode) && name != "CMakeFiles") {
      libraries_in(path, seen);
    } else if (S_ISREG(st.st_mode) && shared_library(name)) {
      seen[name]++;
    }
  }
}

// A shared library the build tree holds twice: a copy a test may load in
// place of the library, refreshed only when what copies it is linked again.
bool copies_a_library(const std::string& build) {
  std::map<std::string, int> seen;
  libraries_in(build, seen);
  for (const auto& kv : seen) {
    if (kv.second > 1) return true;
  }
  return false;
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

const char* const COVERAGE_FLAGS = "-fprofile-instr-generate -fcoverage-mapping";
const char* const COVERAGE_LINK = "-fprofile-instr-generate";

// The options and environment that make a build count the lines it runs,
// added to whatever flags the project was already given.
void instrument(std::vector<std::string>& args, std::vector<std::string>& env) {
  auto add = [&](const std::string& key, const char* flags) {
    for (auto& kv : env) {
      if (kv.rfind(key + "=", 0) == 0) {
        kv += std::string(" ") + flags;
        return;
      }
    }
    env.push_back(key + "=" + flags);
  };
  add("CFLAGS", COVERAGE_FLAGS);
  add("CXXFLAGS", COVERAGE_FLAGS);
  add("LDFLAGS", COVERAGE_LINK);
  for (auto& a : args) {
    auto eq = a.find('=');
    if (a.rfind("-D", 0) != 0 || eq == std::string::npos) continue;
    std::string name = a.substr(2, a.find_first_of(":=") - 2);
    if (name == "CMAKE_C_FLAGS" || name == "CMAKE_CXX_FLAGS") a += std::string(" ") + COVERAGE_FLAGS;
    if (name == "CMAKE_EXE_LINKER_FLAGS" || name == "CMAKE_SHARED_LINKER_FLAGS" ||
        name == "CMAKE_MODULE_LINKER_FLAGS") {
      a += std::string(" ") + COVERAGE_LINK;
    }
  }
}

// The files of a build that carry coverage mapping: its executables and
// shared libraries, which is what a profile is read against.
void instrumented_objects(const std::string& dir, std::vector<std::string>& out) {
  for (const auto& name : entries(dir)) {
    std::string path = dir + "/" + name;
    struct stat st;
    if (lstat(path.c_str(), &st) != 0) continue;
    if (S_ISDIR(st.st_mode)) {
      if (name != "CMakeFiles") instrumented_objects(path, out);
      continue;
    }
    if (!S_ISREG(st.st_mode) || ends_with(name, ".a") || ends_with(name, ".o")) continue;
    bool library = ends_with(name, ".dylib") || ends_with(name, ".so") || name.find(".so.") != std::string::npos;
    if (!library && access(path.c_str(), X_OK) != 0) continue;
    if (read_file(path).find("__llvm_covmap") != std::string::npos) out.push_back(path);
  }
}

// The project's own sources in the copy, with how many lines each has.
void source_lines(const std::string& dir, const std::string& rel, std::map<std::string, int>& out) {
  for (const auto& name : entries(dir)) {
    std::string path = dir + "/" + name;
    struct stat st;
    if (lstat(path.c_str(), &st) != 0) continue;
    std::string file = rel.empty() ? name : rel + "/" + name;
    if (S_ISDIR(st.st_mode)) {
      source_lines(path, file, out);
    } else if (S_ISREG(st.st_mode) && is_source_file(file) && !is_test_file(file)) {
      std::string text = read_file(path);
      int n = static_cast<int>(std::count(text.begin(), text.end(), '\n'));
      if (!text.empty() && text.back() != '\n') n++;
      out[file] = n;
    }
  }
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
  cover_build_ = reni_ + "/cover/" + worker_;
  profiles_ = reni_ + "/profiles/" + worker_;
  inline_limit_ = h.inline_limit_bytes;
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
    if (ran->ok()) *ran = settle_links_in(base_work_, base_build_, nullptr);
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
  unlink_images(build_, rw);
  shared_ = true;
  return BaseStage::Done;
}

Ran Project::configure(const std::atomic<bool>* cancel) { return configure_in(work_, build_, cancel); }

// Ninja when the machine has it and nobody chose a generator: it dates
// what it builds to the nanosecond and rebuilds one file quickly, which is
// what every cast asks of it.
Ran Project::configure_in(const std::string& work, const std::string& build, const std::atomic<bool>* cancel,
                          bool instrumented) {
  std::vector<std::string> argv = {
      "cmake", "-S", work, "-B", build, "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON", "-DBUILD_TESTING=ON"};
  std::vector<std::string> env = env_;
  std::vector<std::string> args = cmake_args_;
  if (instrumented) instrument(args, env);
  bool chosen = false;
  for (const auto& a : cmake_args_) chosen = chosen || a.rfind("-G", 0) == 0;
  for (const auto& kv : env_) chosen = chosen || kv.rfind("CMAKE_GENERATOR=", 0) == 0;
  if (!chosen && on_path("ninja", env_)) {
    argv.emplace_back("-G");
    argv.emplace_back("Ninja");
  }
  // A test loads the shared libraries it links from the build tree when it
  // runs, so one that changed needs no test linked again; a fresh executable
  // costs a cast more to start than to link.
  if (!links_chosen()) {
    argv.push_back(std::string("-DCMAKE_LINK_DEPENDS_NO_SHARED=") + (copies_a_library(build) ? "OFF" : "ON"));
  }
  for (const auto& a : args) argv.push_back(a);
  Ran r = run(argv, work, env, cancel);
  if (!r.ok() && !r.cancelled && r.output.find("Does not match the generator used previously") != std::string::npos) {
    // A build tree of another generator is started over.
    remove_all(build + "/CMakeCache.txt");
    remove_all(build + "/CMakeFiles");
    r = run(argv, work, env, cancel);
  }
  return r;
}

Ran Project::build(const std::atomic<bool>* cancel) { return build_in(build_, cancel); }

bool Project::links_chosen() const {
  for (const auto& a : cmake_args_) {
    if (a.find("CMAKE_LINK_DEPENDS_NO_SHARED") != std::string::npos) return true;
  }
  return false;
}

Ran Project::settle_links(const std::atomic<bool>* cancel) { return settle_links_in(work_, build_, cancel); }

Ran Project::settle_links_in(const std::string& work, const std::string& build, const std::atomic<bool>* cancel) {
  if (links_chosen() || cached(build, "CMAKE_LINK_DEPENDS_NO_SHARED") != "ON" || !copies_a_library(build)) return Ran{};
  Ran r = configure_in(work, build, cancel);
  return r.ok() ? build_in(build, cancel) : r;
}

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
  ctest_names_.clear();
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
    ctest_names_.push_back(name ? name->text : "");
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
  return run_in(tests, cancel, Tree{build_, {}, false});
}

TestRun Project::run_in(const std::vector<const Test*>& tests, const std::atomic<bool>* cancel, const Tree& tree) {
  TestRun out;
  std::vector<const Test*> whole;
  std::map<int, std::vector<const Test*>> by_suite;
  for (const auto* t : tests) (t->suite < 0 ? whole : by_suite[t->suite]).push_back(t);
  if (!whole.empty()) run_ctest(whole, cancel, tree, out);
  for (const auto& kv : by_suite) {
    if (out.cancelled || !out.broken.empty()) break;
    run_cases(kv.first, kv.second, cancel, tree, out);
  }
  return out;
}

void Project::run_cases(int index, const std::vector<const Test*>& tests, const std::atomic<bool>* cancel,
                        const Tree& tree, TestRun& out) {
  Suite s = suites_[static_cast<std::size_t>(index)];
  if (tree.build != build_) {
    // The same executable, as the other build tree has it.
    Rewrites rw = {{build_ + "/", tree.build + "/"}};
    s.executable = rebase(s.executable, rw);
    s.directory = s.directory == build_ ? tree.build : rebase(s.directory, rw);
    for (auto& kv : s.env) kv = rebase(kv, rw);
  }
  for (const auto& kv : tree.env) s.env.push_back(kv);
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
  if (!all && !tree.alone && !ran.cancelled) {
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

void Project::run_ctest(const std::vector<const Test*>& tests, const std::atomic<bool>* cancel, const Tree& tree,
                        TestRun& out) {
  std::string numbers = "0,0,0";
  for (const auto* t : tests) numbers += "," + std::to_string(t->number);
  unlink(junit_.c_str());
  std::vector<std::string> env = env_;
  env.insert(env.end(), tree.env.begin(), tree.env.end());
  Ran r = run({"ctest", "--test-dir", tree.build, "-I", numbers, "--output-junit", junit_, "--output-on-failure"},
              tree.build, env, cancel);
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

std::optional<Tools> Project::coverage_tools(std::string* why) const {
  std::vector<Compiler> compilers;
  for (const auto& dir : entries(build_ + "/CMakeFiles")) {
    for (const char* language : {"C", "CXX"}) {
      bool ok = false;
      std::string text =
          read_file(build_ + "/CMakeFiles/" + dir + "/CMake" + std::string(language) + "Compiler.cmake", &ok);
      if (!ok) continue;
      if (auto c = read_compiler(language, text)) compilers.push_back(*c);
    }
  }
  return tools_for(compilers, env_, why);
}

// A test that fails alone is run again before anything is concluded: a
// flaky one passes, and what it reached then is what it reaches.
constexpr int ALONE_TRIES = 3;

Covered Project::cover(const std::vector<const Test*>& tests) {
  Covered out;
  auto tools = coverage_tools(&out.withheld);
  if (!tools) return out;
  make_dirs(cover_build_);
  cover_build_ = resolved(cover_build_);
  Ran c = configure_in(work_, cover_build_, nullptr, true);
  if (!c.ok()) {
    out.withheld = "cmake could not configure the build instrumented for coverage: " + named(last_lines(c.output, 10));
    return out;
  }
  Ran b = build_in(cover_build_, nullptr);
  if (!b.ok()) {
    out.withheld = "the project did not build instrumented for coverage: " + named(last_lines(b.output, 10));
    return out;
  }
  std::vector<std::string> ids;
  for (const auto* t : tests) ids.push_back(t->id);
  Reach reach(ids);
  out.withheld = covered_tests(tests, *tools, reach);
  remove_all(profiles_);
  if (!out.withheld.empty()) return out;
  std::map<std::string, int> files;
  source_lines(work_, "", files);
  reach.close(files);
  std::string entries = reach.entries();
  if (static_cast<std::int64_t>(entries.size()) <= inline_limit_) {
    out.entries = entries;
    return out;
  }
  std::string path = reni_ + "/coverage/" + worker_ + ".json";
  make_dirs(dir_of(path));
  if (!write_file(path, reach.packed())) {
    out.withheld = "the coverage could not be written to " + path;
    return out;
  }
  out.packed_path = path;
  return out;
}

// Runs each test alone on the instrumented build and adds what it reached;
// why the coverage cannot be stood behind, when it cannot.
std::string Project::covered_tests(const std::vector<const Test*>& tests, const Tools& tools, Reach& reach) {
  Ran listed = run({"ctest", "--test-dir", cover_build_, "--show-only=json-v1"}, cover_build_, env_);
  auto start = listed.output.find('{');
  auto doc = listed.ok() && start != std::string::npos ? parse_json(listed.output.substr(start)) : std::nullopt;
  std::vector<std::string> names;
  if (doc && doc->get("tests")) {
    for (const auto& t : doc->get("tests")->items) names.push_back(t.get("name") ? t.get("name")->text : "");
  }
  if (names != ctest_names_) return "the build instrumented for coverage lists other tests than the build does";
  std::vector<std::string> objects;
  instrumented_objects(cover_build_, objects);
  if (objects.empty()) return "the build instrumented for coverage holds no executable that counts the lines it runs";
  std::vector<std::string> roots = {work_};
  if (reni_ + "/work/" + worker_ != work_) roots.push_back(reni_ + "/work/" + worker_);
  std::atomic<bool> never{false};
  for (std::size_t i = 0; i < tests.size(); i++) {
    const Test* t = tests[i];
    Tree tree{cover_build_, {"LLVM_PROFILE_FILE=" + profiles_ + "/%p.profraw"}, true};
    TestResult res;
    for (int attempt = 0; attempt < ALONE_TRIES; attempt++) {
      remove_all(profiles_);
      make_dirs(profiles_);
      TestRun ran = run_in({t}, &never, tree);
      if (!ran.broken.empty()) return "`" + t->id + "` could not be run alone for coverage: " + ran.broken;
      auto it = ran.by_id.find(t->id);
      res = it == ran.by_id.end() ? TestResult{} : it->second;
      if (res.status == TestResult::Status::Passed) break;
    }
    if (res.status != TestResult::Status::Passed) {
      return "`" + t->id + "` passes in the suite and failed each of " + std::to_string(ALONE_TRIES) +
             " runs alone, so what it reaches alone is not what it reaches there" +
             (res.message.empty() ? "" : ": " + res.message);
    }
    std::vector<std::string> raw;
    for (const auto& name : entries(profiles_)) {
      if (ends_with(name, ".profraw")) raw.push_back(profiles_ + "/" + name);
    }
    if (raw.empty()) {
      reach.unknown(i);
      continue;
    }
    std::string merged = profiles_ + "/merged.profdata";
    std::vector<std::string> merge = {tools.profdata, "merge", "-sparse"};
    merge.insert(merge.end(), raw.begin(), raw.end());
    merge.push_back("-o");
    merge.push_back(merged);
    Ran m = run(merge, profiles_, env_);
    if (!m.ok()) return "llvm-profdata could not merge the profile of `" + t->id + "`: " + last_lines(m.output, 5);
    std::vector<std::string> exp = {tools.cov, "export", "-format=lcov", "-instr-profile=" + merged};
    for (std::size_t o = 0; o < objects.size(); o++) {
      if (o > 0) exp.push_back("-object");
      exp.push_back(objects[o]);
    }
    std::string lcov = profiles_ + "/lines.lcov";
    Ran e = run_to(exp, profiles_, env_, lcov);
    if (!e.ok()) return "llvm-cov could not read the profile of `" + t->id + "`: " + last_lines(e.output, 5);
    Lines lines;
    for (auto& f : reached_lines(read_file(lcov), roots)) {
      if (is_source_file(f.first) && !is_test_file(f.first)) lines.insert(std::move(f));
    }
    reach.add(i, lines);
  }
  return "";
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
