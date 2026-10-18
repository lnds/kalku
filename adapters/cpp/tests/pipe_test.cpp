// The real kalku, over a real pipe, against a real CMake project: the one
// test that catches a line on stdout that is not the protocol.

#include <dirent.h>
#include <poll.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include <chrono>
#include <cstdlib>
#include <map>

#include "json.hpp"
#include "test.hpp"

using kalku::Json;
using kalku::parse_json;
using kalku::testing::here;
using kalku::testing::read;

namespace {

class Kalku {
 public:
  explicit Kalku(const std::string& bin) {
    int in[2], out[2];
    if (pipe(in) != 0 || pipe(out) != 0) return;
    pid_ = fork();
    if (pid_ == 0) {
      dup2(in[0], 0);
      dup2(out[1], 1);
      close(in[1]);
      close(out[0]);
      execl(bin.c_str(), bin.c_str(), static_cast<char*>(nullptr));
      _exit(127);
    }
    close(in[0]);
    close(out[1]);
    to_ = in[1];
    from_ = out[0];
  }

  ~Kalku() {
    if (to_ >= 0) close(to_);
    if (pid_ > 0) {
      kill(pid_, SIGKILL);
      waitpid(pid_, nullptr, 0);
    }
  }

  void send(const std::string& line) {
    std::string l = line + "\n";
    ssize_t n = write(to_, l.data(), l.size());
    (void)n;
  }

  // The next line the kalku wrote, which must be a JSON object; empty when
  // none came in time.
  std::string next(int seconds = 300) {
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
    for (;;) {
      auto nl = buf_.find('\n');
      if (nl != std::string::npos) {
        std::string line = buf_.substr(0, nl);
        buf_.erase(0, nl + 1);
        auto v = parse_json(line);
        if (!v || !v->is_object()) kalku::testing::failed("stdout", "a line that is not the protocol: " + line);
        return line;
      }
      int left = static_cast<int>(
          std::chrono::duration_cast<std::chrono::milliseconds>(deadline - std::chrono::steady_clock::now()).count());
      if (left <= 0) return "";
      struct pollfd p {
        from_, POLLIN, 0
      };
      if (poll(&p, 1, left) <= 0) return "";
      char chunk[65536];
      ssize_t n = read(from_, chunk, sizeof chunk);
      if (n <= 0) return "";
      buf_.append(chunk, static_cast<std::size_t>(n));
    }
  }

  void close_input() {
    close(to_);
    to_ = -1;
  }

  int wait_exit() {
    int status = 0;
    waitpid(pid_, &status, 0);
    pid_ = -1;
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
  }

 private:
  pid_t pid_ = -1;
  int to_ = -1;
  int from_ = -1;
  std::string buf_;
};

std::string field(const std::string& line, const std::string& key) {
  auto v = parse_json(line);
  const Json* f = v ? v->get(key) : nullptr;
  if (!f) return "";
  if (f->is_string()) return f->text;
  if (f->is_integer()) return std::to_string(f->integer);
  if (f->is_bool()) return f->boolean ? "true" : "false";
  return "";
}

// Every file under a directory, with what it holds.
void snapshot(const std::string& dir, const std::string& rel, std::map<std::string, std::string>& out) {
  DIR* d = opendir(dir.c_str());
  if (!d) return;
  while (dirent* e = readdir(d)) {
    std::string name = e->d_name;
    if (name == "." || name == "..") continue;
    std::string path = dir + "/" + name;
    struct stat st;
    if (lstat(path.c_str(), &st) != 0) continue;
    if (S_ISDIR(st.st_mode)) {
      snapshot(path, rel + name + "/", out);
    } else {
      out[rel + name] = read(path);
    }
  }
  closedir(d);
}

std::string temp_reni() {
  const char* tmp = std::getenv("TMPDIR");
  std::string pattern = std::string(tmp ? tmp : "/tmp") + "/kalku-cpp-pipe-XXXXXX";
  char* made = mkdtemp(&pattern[0]);
  return made ? std::string(made) : "";
}

void remove_tree(const std::string& path) {
  std::string cmd = "rm -rf '" + path + "'";
  int ignored = std::system(cmd.c_str());
  (void)ignored;
}

std::string cast_of(int id, const std::string& site, const std::string& tests) {
  return "{\"type\":\"cast\",\"id\":" + std::to_string(id) + ",\"wekufe\":" + kalku::quote(field(site, "site_id")) +
         ",\"site\":" + site + ",\"tests\":" + tests + "}";
}

// Each site of a `sites_found`, exactly as the kalku wrote it, by
// "file:line spell original".
std::map<std::string, std::string> by_place(const std::string& found) {
  std::map<std::string, std::string> by;
  auto doc = parse_json(found);
  if (!doc || !doc->get("sites")) return by;
  for (const auto& s : doc->get("sites")->items) {
    std::string line = std::to_string(s.get("span")->get("start")->get("line")->integer);
    std::string key = s.get("file")->text + ":" + line + " " + s.get("spell")->text + " " + s.get("original")->text;
    std::string json;
    auto at = found.find("{\"site_id\":\"" + s.get("site_id")->text + "\"");
    int depth = 0;
    for (std::size_t i = at; i < found.size(); i++) {
      json += found[i];
      if (found[i] == '{') depth++;
      if (found[i] == '}' && --depth == 0) break;
    }
    by[key] = json;
  }
  return by;
}

bool have_cmake() {
  if (std::system("command -v cmake >/dev/null 2>&1") == 0) return true;
  if (std::getenv("CI")) kalku::testing::failed("cmake", "no cmake on the PATH");
  std::cerr << "  skipped: no cmake on the PATH\n";
  return false;
}

// `hello` and `prepare`, each answered as it should be.
bool started(Kalku& k, const std::string& root, const std::string& reni) {
  k.send("{\"type\":\"hello\",\"id\":1,\"protocol\":1,\"root\":" + kalku::quote(root) +
         ",\"reni\":" + kalku::quote(reni) + ",\"worker\":0,\"inline_limit_bytes\":65536,\"env\":{}}");
  std::string ready = k.next();
  CHECK_EQ(field(ready, "type"), std::string("ready"));
  if (field(ready, "type") != "ready") {
    std::cerr << ready << "\n";
    return false;
  }
  k.send("{\"type\":\"prepare\",\"id\":2}");
  std::string prepared = k.next();
  CHECK_EQ(field(prepared, "type"), std::string("prepared"));
  return field(prepared, "type") == "prepared";
}

struct Expect {
  std::string site;
  std::string outcome;
  std::string killed_by;
};

// Casts each wekufe against both tests and checks what came of it.
void judge(Kalku& k, const std::map<std::string, std::string>& by, const std::vector<Expect>& expected, int& id) {
  for (const auto& e : expected) {
    auto it = by.find(e.site);
    if (it == by.end()) {
      kalku::testing::failed("sites", "no site " + e.site);
      continue;
    }
    k.send(cast_of(id++, it->second, "[\"basics\",\"edges\"]"));
    std::string done = k.next();
    CHECK_EQ(field(done, "outcome") + " " + e.site, e.outcome + " " + e.site);
    CHECK_EQ(field(done, "killed_by") + " " + e.site, e.killed_by + " " + e.site);
  }
}

}  // namespace

TEST("the kalku measures a CMake project over a pipe, and leaves it as it was") {
  if (std::system("command -v cmake >/dev/null 2>&1") != 0) {
    if (std::getenv("CI")) kalku::testing::failed("cmake", "no cmake on the PATH");
    std::cerr << "  skipped: no cmake on the PATH\n";
    return;
  }
  std::string root = here("testdata/projects/ctest");
  std::map<std::string, std::string> before;
  snapshot(root, "", before);
  std::string reni = temp_reni();
  Kalku k(KALKU_CPP_BIN);

  k.send("{\"type\":\"hello\",\"id\":1,\"protocol\":1,\"root\":" + kalku::quote(root) +
         ",\"reni\":" + kalku::quote(reni) + ",\"worker\":0,\"inline_limit_bytes\":65536,\"env\":{}}");
  std::string ready = k.next();
  CHECK_EQ(field(ready, "type"), std::string("ready"));
  if (field(ready, "type") != "ready") {
    std::cerr << ready << "\n";
    remove_tree(reni);
    return;
  }

  k.send("{\"type\":\"prepare\",\"id\":2}");
  std::string prepared = k.next();
  CHECK_EQ(field(prepared, "type"), std::string("prepared"));

  k.send(
      "{\"type\":\"sites\",\"id\":3,\"files\":[\"src/calc.cpp\",\"include/calc.hpp\",\"tests/edges_test.cpp\"],"
      "\"spells\":[\"arm\",\"compare\",\"connect\",\"negate\",\"literal\",\"call\"],\"exclude_calls\":[]}");
  std::string found = k.next();
  CHECK_EQ(field(found, "type"), std::string("sites_found"));
  auto doc = parse_json(found);
  auto by = by_place(found);
  const auto& skipped = doc->get("skipped")->items;
  CHECK_EQ(skipped.size(), static_cast<std::size_t>(1));
  if (!skipped.empty()) CHECK_EQ(skipped[0].get("reason")->text, std::string("test_file"));

  k.send("{\"type\":\"baseline\",\"id\":4}");
  std::string baseline = k.next();
  CHECK_EQ(field(baseline, "status"), std::string("green"));
  CHECK(baseline.find("\"test\":\"basics\"") != std::string::npos);
  CHECK(baseline.find("\"test\":\"edges\"") != std::string::npos);

  const std::string both = "[\"basics\",\"edges\"]";
  struct Expect {
    std::string site;
    std::string outcome;
    std::string killed_by;
  };
  std::vector<Expect> expected = {
      {"include/calc.hpp:11 compare >", "killed", "edges"},
      {"src/calc.cpp:17 literal 1", "killed", "edges"},
      {"src/calc.cpp:12 compare ==", "crashed", ""},
      {"src/calc.cpp:7 compare >", "survived", ""},
  };
  int id = 10;
  for (const auto& e : expected) {
    auto it = by.find(e.site);
    if (it == by.end()) {
      kalku::testing::failed("sites", "no site " + e.site);
      continue;
    }
    k.send(cast_of(id++, it->second, both));
    std::string done = k.next();
    CHECK_EQ(field(done, "outcome") + " " + e.site, e.outcome + " " + e.site);
    CHECK_EQ(field(done, "killed_by"), e.killed_by);
    if (e.outcome == "crashed") CHECK(field(done, "message").find("edges") != std::string::npos);
  }

  // A wekufe that does not compile is a compile error, not a kill.
  auto lt = by.find("src/calc.cpp:6 compare <");
  CHECK(lt != by.end());
  if (lt != by.end()) {
    std::string broken = lt->second;
    auto r = broken.find("\"replacement\":\"<=\"");
    CHECK(r != std::string::npos);
    if (r != std::string::npos) broken.replace(r, 18, "\"replacement\":\"<@\"");
    k.send(cast_of(id++, broken, both));
    std::string done = k.next();
    CHECK_EQ(field(done, "outcome"), std::string("compile_error"));
    CHECK(field(done, "message").find("calc.cpp") != std::string::npos);
    CHECK(field(done, "message").find(reni) == std::string::npos);
  }

  // An abort stops the cast where it is, and the next cast finds the copy
  // as it was.
  // A wekufe that loops for good is still running when the abort comes.
  auto loops = by.find("src/calc.cpp:25 literal 2");
  auto neg = by.find("src/calc.cpp:6 negate v < lo");
  CHECK(loops != by.end());
  CHECK(neg != by.end());
  if (loops != by.end() && neg != by.end()) {
    int cast_id = id++;
    k.send(cast_of(cast_id, loops->second, both));
    usleep(1500 * 1000);
    k.send("{\"type\":\"abort\",\"id\":" + std::to_string(id++) + ",\"cast\":" + std::to_string(cast_id) + "}");
    std::string aborted = k.next();
    CHECK_EQ(field(aborted, "type"), std::string("aborted"));
    CHECK_EQ(field(aborted, "restored"), std::string("true"));
    k.send(cast_of(id++, neg->second, both));
    std::string done = k.next();
    CHECK_EQ(field(done, "outcome"), std::string("killed"));
    CHECK_EQ(field(done, "killed_by"), std::string("basics"));
  }

  k.send("{\"type\":\"shutdown\",\"id\":99}");
  CHECK_EQ(field(k.next(), "type"), std::string("bye"));
  k.close_input();
  CHECK_EQ(k.wait_exit(), 0);

  std::map<std::string, std::string> after;
  snapshot(root, "", after);
  CHECK(before == after);
  remove_tree(reni);
}

TEST("the kalku measures a C project over a pipe, and leaves it as it was") {
  if (!have_cmake()) return;
  std::string root = here("testdata/projects/c_ctest");
  std::map<std::string, std::string> before;
  snapshot(root, "", before);
  std::string reni = temp_reni();
  Kalku k(KALKU_CPP_BIN);
  if (!started(k, root, reni)) {
    remove_tree(reni);
    return;
  }

  k.send(
      "{\"type\":\"sites\",\"id\":3,\"files\":[\"src/calc.c\",\"include/calc.h\",\"tests/edges_test.c\"],"
      "\"spells\":[\"arm\",\"compare\",\"connect\",\"negate\",\"literal\",\"call\"],\"exclude_calls\":[]}");
  std::string found = k.next();
  CHECK_EQ(field(found, "type"), std::string("sites_found"));
  auto by = by_place(found);
  auto doc = parse_json(found);
  const auto& skipped = doc->get("skipped")->items;
  CHECK_EQ(skipped.size(), static_cast<std::size_t>(1));
  if (!skipped.empty()) CHECK_EQ(skipped[0].get("reason")->text, std::string("test_file"));
  // What a macro writes is not a site, wherever it is used.
  for (const auto& kv : by) CHECK(kv.first.rfind("src/calc.c:52 ", 0) != 0);
  CHECK(by.count("src/calc.c:33 arm case 9:\n      return \"A\";\n    ") == 1);
  CHECK(by.count("src/calc.c:23 call count();") == 1);

  k.send("{\"type\":\"baseline\",\"id\":4}");
  std::string baseline = k.next();
  CHECK_EQ(field(baseline, "status"), std::string("green"));

  int id = 10;
  judge(k, by,
        {
            // A header's wekufe is compiled into every test that includes it.
            {"include/calc.h:16 compare >", "killed", "edges"},
            {"src/calc.c:12 negate v < lo", "killed", "basics"},
            {"src/calc.c:18 compare ==", "crashed", ""},
            {"src/calc.c:13 compare >", "survived", ""},
        },
        id);

  auto lt = by.find("src/calc.c:12 compare <");
  CHECK(lt != by.end());
  if (lt != by.end()) {
    std::string broken = lt->second;
    auto r = broken.find("\"replacement\":\"<=\"");
    CHECK(r != std::string::npos);
    if (r != std::string::npos) broken.replace(r, 18, "\"replacement\":\"<@\"");
    k.send(cast_of(id++, broken, "[\"basics\",\"edges\"]"));
    std::string done = k.next();
    CHECK_EQ(field(done, "outcome"), std::string("compile_error"));
    CHECK(field(done, "message").find("calc.c") != std::string::npos);
  }

  // A wekufe that loops for good is stopped where it is.
  auto loops = by.find("src/calc.c:44 negate n >= 10");
  CHECK(loops != by.end());
  if (loops != by.end()) {
    int cast_id = id++;
    k.send(cast_of(cast_id, loops->second, "[\"basics\",\"edges\"]"));
    usleep(1500 * 1000);
    k.send("{\"type\":\"abort\",\"id\":" + std::to_string(id++) + ",\"cast\":" + std::to_string(cast_id) + "}");
    std::string aborted = k.next();
    CHECK_EQ(field(aborted, "type"), std::string("aborted"));
    CHECK_EQ(field(aborted, "restored"), std::string("true"));
  }

  k.send("{\"type\":\"shutdown\",\"id\":99}");
  CHECK_EQ(field(k.next(), "type"), std::string("bye"));
  k.close_input();
  CHECK_EQ(k.wait_exit(), 0);

  std::map<std::string, std::string> after;
  snapshot(root, "", after);
  CHECK(before == after);
  remove_tree(reni);
}

TEST("a C project tested with Unity is measured one test executable at a time") {
  if (!have_cmake()) return;
  std::string root = here("testdata/projects/c_unity");
  std::string reni = temp_reni();
  Kalku k(KALKU_CPP_BIN);
  if (!started(k, root, reni)) {
    remove_tree(reni);
    return;
  }
  k.send(
      "{\"type\":\"sites\",\"id\":3,\"files\":[\"src/ring.c\"],"
      "\"spells\":[\"arm\",\"compare\",\"connect\",\"negate\",\"literal\",\"call\"],\"exclude_calls\":[]}");
  auto by = by_place(k.next());
  k.send("{\"type\":\"baseline\",\"id\":4}");
  std::string baseline = k.next();
  CHECK_EQ(field(baseline, "status"), std::string("green"));
  CHECK(baseline.find("\"test\":\"basics\"") != std::string::npos);
  CHECK(baseline.find("\"test\":\"edges\"") != std::string::npos);

  // The second wekufe is killed by the second executable, after one that
  // passes with it.
  int id = 10;
  judge(k, by,
        {
            {"src/ring.c:8 compare ==", "killed", "basics"},
            {"src/ring.c:20 literal 1", "killed", "edges"},
        },
        id);
  k.send("{\"type\":\"shutdown\",\"id\":99}");
  CHECK_EQ(field(k.next(), "type"), std::string("bye"));
  k.close_input();
  CHECK_EQ(k.wait_exit(), 0);
  remove_tree(reni);
}

TEST("input that ends with no shutdown ends the kalku at once") {
  Kalku k(KALKU_CPP_BIN);
  k.close_input();
  CHECK_EQ(k.wait_exit(), 0);
}
