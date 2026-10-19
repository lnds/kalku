#include "process.hpp"

#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include <chrono>
#include <cstring>
#include <mutex>
#include <set>

namespace kalku {

namespace {

std::mutex groups_mu;
std::set<pid_t> groups;

constexpr std::size_t KEPT = 256 * 1024;

std::string path_of(const std::vector<std::string>& env) {
  for (const auto& kv : env) {
    if (kv.rfind("PATH=", 0) == 0) return kv.substr(5);
  }
  return "/usr/bin:/bin";
}

// The program as the command's own PATH finds it.
std::string resolve(const std::string& program, const std::vector<std::string>& env) {
  if (program.find('/') != std::string::npos) return program;
  std::string path = path_of(env);
  std::size_t from = 0;
  while (from <= path.size()) {
    std::size_t to = path.find(':', from);
    if (to == std::string::npos) to = path.size();
    std::string dir = path.substr(from, to - from);
    if (dir.empty()) dir = ".";
    std::string candidate = dir + "/" + program;
    struct stat st;
    if (stat(candidate.c_str(), &st) == 0 && S_ISREG(st.st_mode) && access(candidate.c_str(), X_OK) == 0) {
      return candidate;
    }
    from = to + 1;
  }
  return program;
}

void keep(std::string& out, const char* data, std::size_t n) {
  out.append(data, n);
  if (out.size() > 2 * KEPT) out.erase(0, out.size() - KEPT);
}

}  // namespace

Ran run(const std::vector<std::string>& argv, const std::string& dir, const std::vector<std::string>& env,
        const std::atomic<bool>* cancel) {
  return run_to(argv, dir, env, "", cancel);
}

Ran run_to(const std::vector<std::string>& argv, const std::string& dir, const std::vector<std::string>& env,
           const std::string& out_file, const std::atomic<bool>* cancel) {
  Ran r;
  auto started = std::chrono::steady_clock::now();
  int fds[2];
  if (argv.empty() || pipe(fds) != 0) {
    r.unstarted = true;
    return r;
  }
  std::string program = resolve(argv[0], env);
  std::vector<char*> cargv;
  for (const auto& a : argv) cargv.push_back(const_cast<char*>(a.c_str()));
  cargv.push_back(nullptr);
  std::vector<char*> cenv;
  for (const auto& e : env) cenv.push_back(const_cast<char*>(e.c_str()));
  cenv.push_back(nullptr);

  std::unique_lock<std::mutex> lock(groups_mu);
  pid_t pid = fork();
  if (pid == 0) {
    setpgid(0, 0);
    int devnull = open("/dev/null", O_RDONLY);
    // stdin is the protocol: a command that read it would eat requests.
    dup2(devnull, 0);
    int out = out_file.empty() ? fds[1] : open(out_file.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (out < 0) _exit(126);
    dup2(out, 1);
    dup2(fds[1], 2);
    close(fds[0]);
    close(fds[1]);
    if (!dir.empty() && chdir(dir.c_str()) != 0) _exit(127);
    execve(program.c_str(), cargv.data(), cenv.data());
    const char* msg = "kalku-cpp: cannot run the command\n";
    ssize_t ignored = write(2, msg, std::strlen(msg));
    (void)ignored;
    _exit(127);
  }
  if (pid < 0) {
    lock.unlock();
    close(fds[0]);
    close(fds[1]);
    r.unstarted = true;
    return r;
  }
  setpgid(pid, pid);
  groups.insert(pid);
  lock.unlock();
  close(fds[1]);

  char buf[65536];
  bool open_pipe = true;
  int status = 0;
  bool exited = false;
  while (open_pipe || !exited) {
    if (cancel && cancel->load() && !r.cancelled) {
      r.cancelled = true;
      kill(-pid, SIGKILL);
    }
    if (!exited) {
      pid_t w = waitpid(pid, &status, WNOHANG);
      if (w == pid) {
        exited = true;
        // What the command left behind in its group ends with it, so a
        // child that holds the pipe open cannot keep this waiting.
        kill(-pid, SIGKILL);
      }
    }
    if (!open_pipe) {
      if (!exited) {
        waitpid(pid, &status, 0);
        exited = true;
      }
      break;
    }
    struct pollfd p {
      fds[0], POLLIN, 0
    };
    int got = poll(&p, 1, 100);
    if (got > 0) {
      ssize_t n = read(fds[0], buf, sizeof buf);
      if (n > 0) {
        keep(r.output, buf, static_cast<std::size_t>(n));
        ssize_t ignored = write(2, buf, static_cast<std::size_t>(n));
        (void)ignored;
      } else if (n == 0 || (n < 0 && errno != EINTR && errno != EAGAIN)) {
        open_pipe = false;
      }
    }
  }
  close(fds[0]);
  {
    std::lock_guard<std::mutex> g(groups_mu);
    groups.erase(pid);
  }
  if (WIFEXITED(status)) {
    r.exit_code = WEXITSTATUS(status);
    if (r.exit_code == 127 && r.output.find("kalku-cpp: cannot run the command") != std::string::npos) {
      r.unstarted = true;
    }
  } else if (WIFSIGNALED(status)) {
    r.signal = WTERMSIG(status);
  }
  if (r.output.size() > KEPT) r.output.erase(0, r.output.size() - KEPT);
  r.duration_ms = static_cast<long>(
      std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - started).count());
  return r;
}

bool on_path(const std::string& program, const std::vector<std::string>& env) {
  return resolve(program, env) != program;
}

std::string output_of(const std::vector<std::string>& argv, const std::string& dir,
                      const std::vector<std::string>& env) {
  Ran r = run(argv, dir, env);
  return r.ok() ? r.output : "";
}

void leave(int code) {
  groups_mu.lock();
  for (pid_t g : groups) kill(-g, SIGKILL);
  _exit(code);
}

}  // namespace kalku
