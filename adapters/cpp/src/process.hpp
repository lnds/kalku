// Commands the kalku runs: cmake, the build, ctest. Each is a process group
// of its own, because a build and a test runner start children of their
// own, and ending the command alone would leave those running.
#pragma once

#include <atomic>
#include <string>
#include <vector>

namespace kalku {

struct Ran {
  // The command could not be started at all.
  bool unstarted = false;
  int exit_code = 0;
  int signal = 0;
  bool cancelled = false;
  // What it wrote to stdout and stderr together, the end of it if long.
  std::string output;
  long duration_ms = 0;

  bool ok() const { return !unstarted && !cancelled && signal == 0 && exit_code == 0; }
};

// Runs argv in dir with exactly env. Everything it writes is kept and also
// goes to this process's stderr, which is not the protocol. When `cancel`
// becomes true the whole group is killed.
Ran run(const std::vector<std::string>& argv, const std::string& dir, const std::vector<std::string>& env,
        const std::atomic<bool>* cancel = nullptr);

// The same, with what the command writes to stdout in `out_file` alone.
Ran run_to(const std::vector<std::string>& argv, const std::string& dir, const std::vector<std::string>& env,
           const std::string& out_file, const std::atomic<bool>* cancel = nullptr);

// True when the PATH in env finds the program.
bool on_path(const std::string& program, const std::vector<std::string>& env);

// The output of a command that went well, or nothing.
std::string output_of(const std::vector<std::string>& argv, const std::string& dir,
                      const std::vector<std::string>& env);

// Kills every group still running, then ends this process. Held to the end,
// so that no command starts after the ones under way were killed.
[[noreturn]] void leave(int code);

}  // namespace kalku
