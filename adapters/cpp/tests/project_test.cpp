// The command a file is compiled with, chosen from the build's
// compile_commands.json.

#include "project.hpp"

#include <sys/stat.h>
#include <unistd.h>

#include <climits>
#include <cstdlib>
#include <fstream>

#include "test.hpp"

using namespace kalku;

namespace {

std::string temp_reni() {
  const char* tmp = std::getenv("TMPDIR");
  std::string pattern = std::string(tmp ? tmp : "/tmp") + "/kalku-cpp-project-XXXXXX";
  char* made = mkdtemp(&pattern[0]);
  char buf[PATH_MAX];
  return made && realpath(made, buf) ? std::string(buf) : "";
}

void remove_tree(const std::string& path) {
  std::string cmd = "rm -rf '" + path + "'";
  int ignored = std::system(cmd.c_str());
  (void)ignored;
}

// A project whose build compiles these files, the n-th with `-DN=n`.
Project project_compiling(const std::string& reni, const std::vector<std::string>& files) {
  Hello h;
  h.root = reni + "/root";
  h.reni = reni;
  std::string work = reni + "/work/0";
  std::string build = reni + "/build/0";
  mkdir((reni + "/build").c_str(), 0755);
  mkdir(build.c_str(), 0755);
  std::string json = "[";
  for (std::size_t i = 0; i < files.size(); i++) {
    const std::string& f = files[i];
    std::string dir = f[0] == '/' ? "/" : work;
    json += (i ? "," : "") + std::string("{\"directory\":\"") + dir + "\",\"file\":\"" + f +
            "\",\"arguments\":[\"cc\",\"-DN=" + std::to_string(i) + "\",\"-c\",\"" + f + "\"]}";
  }
  std::ofstream(build + "/compile_commands.json") << json << "]";
  return Project(h, {});
}

// Which entry was chosen for a file, by its `-DN=`; -1 for none.
int chosen(const Project& p, const std::string& file) {
  auto index = p.compile_index();
  auto c = index ? p.compile_of(file, *index) : std::nullopt;
  auto alone = p.compile_of(file);
  CHECK_EQ(c.has_value(), alone.has_value());
  if (!c || !alone) return -1;
  CHECK(c->args == alone->args);
  CHECK_EQ(c->directory, alone->directory);
  for (const auto& a : c->args) {
    if (a.rfind("-DN=", 0) == 0) return std::atoi(a.c_str() + 4);
  }
  return -1;
}

}  // namespace

TEST("a file's own command wins, the last of several; else the first beside it; else the first of the project's own") {
  std::string reni = temp_reni();
  Project p =
      project_compiling(reni, {"src/a.cpp", "lib/b.cpp", "src/a.cpp", "lib/c.cpp", "tests/t.cpp", "/elsewhere/x.cpp"});
  CHECK_EQ(chosen(p, "src/a.cpp"), 2);
  CHECK_EQ(chosen(p, "lib/c.cpp"), 3);
  CHECK_EQ(chosen(p, "lib/z.h"), 1);
  CHECK_EQ(chosen(p, "src/new.cpp"), 0);
  CHECK_EQ(chosen(p, "other/q.cpp"), 0);
  CHECK_EQ(chosen(p, "other/q.h"), 0);
  auto c = p.compile_of("src/a.cpp");
  CHECK(c.has_value());
  // The file compiled is not among the arguments.
  if (c) CHECK_EQ(c->args.size(), static_cast<std::size_t>(3));
  remove_tree(reni);
}

TEST("a header of a project that compiles only its tests takes a test's command, and a source none") {
  std::string reni = temp_reni();
  Project p = project_compiling(reni, {"/elsewhere/x.cpp", "tests/t.cpp", "tests/u.cpp"});
  CHECK_EQ(chosen(p, "include/lib.hpp"), 1);
  CHECK_EQ(chosen(p, "src/lib.cpp"), -1);
  CHECK_EQ(chosen(p, "tests/v.cpp"), 1);
  remove_tree(reni);
}

TEST("a build with no compile_commands.json compiles nothing") {
  std::string reni = temp_reni();
  Project p = project_compiling(reni, {});
  std::string path = reni + "/build/0/compile_commands.json";
  unlink(path.c_str());
  CHECK(!p.compile_index().has_value());
  CHECK(!p.compile_of("src/a.cpp").has_value());
  remove_tree(reni);
}
