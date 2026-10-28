#include "service.hpp"

#include <sys/stat.h>

#include <chrono>
#include <cstdio>
#include <iostream>
#include <set>

#include "sites.hpp"
#include "version.hpp"

namespace kalku {

namespace {

// The oldest CMake this kalku drives: the first whose CTest writes a JUnit
// report, which is where each test's own verdict is read.
constexpr int OLDEST[2] = {3, 21};

const std::vector<std::string> CAPABILITIES = {
    "cast", "abort", "cast_after_baseline", "recompile_dependents", "per_test_coverage", "partial_baseline"};

Reply say(const std::string& line) { return Reply{line, false, false}; }

std::int64_t since(std::chrono::steady_clock::time_point t) {
  return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t).count();
}

bool file_exists(const std::string& p) {
  struct stat st;
  return stat(p.c_str(), &st) == 0;
}

// `cmake version 3.28.3` → {3, 28}.
bool recent_enough(const std::string& line, std::string* version) {
  auto at = line.find("version ");
  if (at == std::string::npos) return false;
  std::string v = line.substr(at + 8);
  v = v.substr(0, v.find_first_of(" \n\r"));
  *version = v;
  int major = 0, minor = 0;
  if (std::sscanf(v.c_str(), "%d.%d", &major, &minor) != 2) return false;
  return major > OLDEST[0] || (major == OLDEST[0] && minor >= OLDEST[1]);
}

std::string first_line(const std::string& s) { return s.substr(0, s.find('\n')); }

// What a build said that a reader of a compile error needs: the lines that
// say error, or the end of it all.
std::string diagnostic(const std::string& output) {
  std::string out;
  std::set<std::string> seen;
  std::size_t from = 0;
  int kept = 0;
  while (from < output.size() && kept < 20) {
    std::size_t to = output.find('\n', from);
    if (to == std::string::npos) to = output.size();
    std::string line = output.substr(from, to - from);
    // A header is compiled once for every file that includes it, and says
    // the same thing each time.
    if (line.find("error") != std::string::npos && line.find("make") == std::string::npos &&
        line.find("ninja: build stopped") == std::string::npos && line.find("errors generated") == std::string::npos &&
        line.find("error generated") == std::string::npos && seen.insert(line).second) {
      out += (out.empty() ? "" : "\n") + line;
      kept++;
    }
    from = to + 1;
  }
  if (out.empty()) out = output.size() > 2000 ? output.substr(output.size() - 2000) : output;
  return out.empty() ? "the build failed and said nothing" : out;
}

}  // namespace

Reply refuse(const DecodeError& e) {
  return say(error(e.id.value_or(0), "bad_request", e.kind + ": " + e.detail, false));
}

Service::Service() = default;

Reply Service::handle(const Request& r, const std::atomic<bool>& cancel) {
  const std::string& t = r.type;
  if (t == "hello") return greet(r);
  if (t == "shutdown") return Reply{bye(r.id), false, true};
  // The cast was stopped as the abort was heard, and put its file back
  // before it gave up: nothing it did outlives it.
  if (t == "abort") return say(aborted(r.id, r.abort, true));
  if (t == "delegate")
    return say(error(r.id, "bad_request", "this kalku casts its own wekufe and does not delegate", false));
  if (!project_) return say(error(r.id, "not_ready", "`hello` has to come first", false));
  if (t == "sites") return say(sites(r.id, r.sites));
  if (t == "prepare") return say(prepare(r.id));
  if (t == "reload") return say(reload(r.id, r.reload));
  // Every cast builds and runs in processes of its own, so there is no state
  // held here for a wekufe to dirty.
  if (t == "reset") return say(reset_done(r.id, true, 0));
  if (!prepared_) return say(error(r.id, "not_ready", "`prepare` has to come first", false));
  if (t == "baseline") return say(baseline(r.id, r.reuse));
  if (t == "cast") return cast(r.id, r.cast, cancel);
  return say(error(r.id, "bad_request", "this kalku does not answer `" + t + "`", false));
}

Reply Service::greet(const Request& r) {
  const Hello& h = r.hello;
  if (h.protocol != PROTOCOL) {
    return say(error(
        r.id, "protocol_mismatch",
        "kalku speaks protocol " + std::to_string(PROTOCOL) + ", kaikai side speaks " + std::to_string(h.protocol),
        true));
  }
  std::vector<std::string> cmake_args;
  if (h.options) {
    std::string bad;
    for (const auto& f : h.options->fields) {
      if (f.first != "cmake_args") {
        bad += (bad.empty() ? "" : ", ") + ("cpp." + f.first);
        continue;
      }
      if (!f.second.is_array()) {
        bad += (bad.empty() ? "" : ", ") + std::string("cpp.cmake_args (a list of strings)");
        continue;
      }
      for (const auto& a : f.second.items) {
        if (!a.is_string()) {
          bad += (bad.empty() ? "" : ", ") + std::string("cpp.cmake_args (a list of strings)");
          break;
        }
        cmake_args.push_back(a.text);
      }
    }
    if (!bad.empty()) {
      return say(error(r.id, "bad_options",
                       bad + ": the C and C++ kalku takes `cmake_args`, a list of options for cmake", true));
    }
  }
  if (!file_exists(h.root + "/CMakeLists.txt")) {
    return say(error(r.id, "not_a_cmake_project",
                     h.root + " has no CMakeLists.txt: the C and C++ kalku measures a CMake project, from its root",
                     true));
  }
  auto project = std::make_unique<Project>(h, cmake_args);
  std::string cmake = first_line(output_of({"cmake", "--version"}, h.root, project->env()));
  if (cmake.empty()) return say(error(r.id, "toolchain_missing", "no `cmake` to run on the PATH", true));
  std::string version;
  if (!recent_enough(cmake, &version)) {
    return say(error(r.id, "unsupported_toolchain",
                     "cmake " + version + " is older than " + std::to_string(OLDEST[0]) + "." +
                         std::to_string(OLDEST[1]) + ", the oldest CMake this kalku drives",
                     true));
  }
  if (first_line(output_of({"ctest", "--version"}, h.root, project->env())).empty()) {
    return say(error(r.id, "toolchain_missing", "no `ctest` to run on the PATH", true));
  }
  std::string why;
  clang_ = cx::load(&why);
  if (!clang_) return say(error(r.id, "toolchain_missing", why, true));
  project_ = std::move(project);
  std::string runtime = "CMake " + version + " / " + cx::text(*clang_, clang_->getClangVersion());
  return say(ready(r.id, VERSION, runtime, CAPABILITIES));
}

// The copy, configured: what both reading the project and building it need.
bool Service::ready_to_read(std::string* why) {
  if (!copied_) {
    if (!project_->copy(why)) {
      *why = "cannot copy the project into the reni: " + *why;
      return false;
    }
    copied_ = true;
  }
  if (!project_->configured()) {
    Ran c = project_->configure();
    if (!c.ok()) {
      *why = "cmake could not configure the project: " + diagnostic(project_->named(c.output));
      return false;
    }
  }
  return true;
}

std::string Service::prepare(std::int64_t id) {
  auto started = std::chrono::steady_clock::now();
  copied_ = false;
  std::string why;
  Ran ran;
  BaseStage stage = project_->shares_build() ? project_->from_base(&ran, &why) : BaseStage::Unshared;
  if (stage == BaseStage::Copy) {
    return error(id, "prepare_failed", "cannot copy the project into the reni: " + why, true);
  }
  if (stage == BaseStage::Configure) {
    return error(id, "prepare_failed",
                 "cmake could not configure the project: " + diagnostic(project_->named(ran.output)), true);
  }
  if (stage == BaseStage::Build) {
    return error(id, "prepare_failed", "the project did not build: " + diagnostic(project_->named(ran.output)), true);
  }
  copied_ = stage == BaseStage::Done;
  if (stage == BaseStage::Unshared) {
    if (!ready_to_read(&why)) return error(id, "prepare_failed", why, true);
    // Configured again whatever the copy held, so the build tree is the one
    // this copy and these options make.
    Ran c = project_->configure();
    if (!c.ok()) {
      return error(id, "prepare_failed",
                   "cmake could not configure the project: " + diagnostic(project_->named(c.output)), true);
    }
  }
  // What the copy of the base left out of date, if anything, is built here.
  Ran b = project_->build();
  if (b.ok()) b = project_->settle_links();
  if (!b.ok()) {
    return error(id, "prepare_failed", "the project did not build: " + diagnostic(project_->named(b.output)), true);
  }
  if (!project_->list_tests(&why)) return error(id, "prepare_failed", why, true);
  compiled_ = project_->read_by_compiles();
  prepared_ = true;
  return prepared(id, since(started), project_->translation_units());
}

// Every test runs but one the kaikai side offers back with the fingerprint
// it has now: nothing it could observe has changed since it passed.
std::string Service::baseline(std::int64_t id, const std::map<std::string, std::string>& reuse) {
  auto started = std::chrono::steady_clock::now();
  std::vector<std::string> prints = project_->fingerprints();
  std::vector<const Test*> all;
  std::map<std::string, std::string> print_of;
  std::vector<std::string> reused;
  for (std::size_t i = 0; i < project_->tests().size(); i++) {
    const Test& t = project_->tests()[i];
    print_of[t.id] = prints[i];
    auto offered = reuse.find(t.id);
    if (offered != reuse.end() && !prints[i].empty() && offered->second == prints[i]) {
      reused.push_back(t.id);
    } else {
      all.push_back(&t);
    }
  }
  if (all.empty()) {
    return baseline_done(id, since(started), {}, {}, project_->differences(), Covered{"[]", "", ""}, reused);
  }
  std::atomic<bool> never{false};
  TestRun ran = project_->run_tests(all, &never);
  if (!ran.broken.empty()) return error(id, "baseline_failed", ran.broken, true);
  std::vector<Timed> timed;
  std::vector<Failure> failures;
  std::vector<const Test*> passed;
  for (const auto* t : all) {
    auto it = ran.by_id.find(t->id);
    long ms = it == ran.by_id.end() ? 0 : it->second.duration_ms;
    timed.push_back(Timed{t->id, t->file, ms, print_of[t->id]});
    if (it == ran.by_id.end()) {
      failures.push_back(Failure{t->id, "no verdict was reported for it"});
    } else if (it->second.status != TestResult::Status::Passed) {
      failures.push_back(Failure{t->id, it->second.message});
    } else {
      passed.push_back(t);
    }
  }
  // A test that failed judges nothing, so only what passed is measured.
  Covered covered =
      passed.empty() ? Covered{"", "", "no test passed, so no test reaches a line"} : project_->cover(passed);
  return baseline_done(id, since(started), timed, failures, project_->differences(), covered, reused);
}

std::string Service::sites(std::int64_t id, const SitesRequest& r) {
  std::vector<Site> found;
  std::vector<Skipped> skipped;
  std::string why;
  bool readable = ready_to_read(&why);
  for (const auto& file : r.files) {
    auto skip = [&](const std::string& reason, const std::string& message) {
      skipped.push_back(Skipped{file, reason, message});
    };
    auto leave = [&](const std::string& reason, const std::string& message) {
      skipped.push_back(Skipped{file, reason, message, true});
    };
    if (!is_source_file(file)) {
      leave("not_source", "`" + file + "` is not a C or C++ source file");
      continue;
    }
    if (is_test_file(file)) {
      leave("test_file", "`" + file + "` is part of the suite, which is the oracle");
      continue;
    }
    if (!readable) {
      skip("not_built", why);
      continue;
    }
    std::FILE* f = std::fopen((project_->root() + "/" + file).c_str(), "rb");
    if (!f) {
      skip("unreadable", "cannot read `" + file + "`");
      continue;
    }
    std::string text;
    char buf[65536];
    std::size_t n;
    while ((n = std::fread(buf, 1, sizeof buf, f)) > 0) text.append(buf, n);
    std::fclose(f);
    auto compile = project_->compile_of(file);
    if (!compile) {
      skip("not_built", "no compile command builds `" + file + "` or a file beside it");
      continue;
    }
    SiteSearch s;
    s.file = file;
    s.path = project_->work() + "/" + file;
    s.text = text;
    s.args = compile->args;
    s.directory = compile->directory;
    s.header = is_header(file);
    s.c = compile->c;
    s.spells = r.spells;
    s.exclude_calls = r.exclude_calls;
    Searched got = find_sites(*clang_, s);
    if (got.skipped) {
      got.skipped->message = project_->named(got.skipped->message);
      skipped.push_back(*got.skipped);
    } else {
      found.insert(found.end(), got.sites.begin(), got.sites.end());
    }
  }
  return sites_found(id, found, skipped);
}

Reply Service::cast(std::int64_t id, const CastRequest& c, const std::atomic<bool>& cancel) {
  std::vector<const Test*> tests, rest;
  for (const auto& name : c.tests) {
    const Test* t = project_->lookup(name);
    if (!t) return say(error(id, "unknown_test", "no test " + name, false));
    tests.push_back(t);
  }
  for (const auto& name : c.rest) {
    const Test* t = project_->lookup(name);
    if (!t) return say(error(id, "unknown_test", "no test " + name, false));
    rest.push_back(t);
  }
  const Site& site = c.site;
  if (!site.end) return say(error(id, "bad_request", "the site has no end", false));
  auto text = project_->read(site.file);
  if (!text) return say(error(id, "bad_request", "cannot read `" + site.file + "` in the reni", false));
  long from = site.start.byte, to = site.end->byte;
  if (from < 0 || to < from || static_cast<std::size_t>(to) > text->size() ||
      (site.original &&
       text->compare(static_cast<std::size_t>(from), static_cast<std::size_t>(to - from), *site.original) != 0)) {
    return say(error(id, "bad_request", "`" + site.file + "` is not the text this site was found in", false));
  }
  auto started = std::chrono::steady_clock::now();
  // A file no compile reads, as one an option leaves out of the build, is
  // code no test can run: its wekufe passing every test says nothing.
  if (compiled_ && !compiled_->count(site.file)) {
    return say(cast_done(id, c.wekufe,
                         Outcome{"no_coverage", "", "no build of the project compiles `" + site.file + "`"},
                         since(started)));
  }
  if (tests.empty() && rest.empty()) {
    return say(cast_done(id, c.wekufe, Outcome{"no_coverage", "", "no test was asked to judge it"}, since(started)));
  }
  if (!project_->write(site.file, splice(*text, from, to, site.replacement))) {
    return say(error(id, "cast_failed", "cannot write the wekufe into the copy", true));
  }
  // Whatever ends the cast, the copy holds the original again before it is
  // answered for.
  auto restore = [&]() { return project_->write(site.file, *text); };
  Ran built = project_->build(&cancel);
  if (built.cancelled) {
    restore();
    return Reply{"", true, false};
  }
  if (!built.ok()) {
    if (!restore()) return say(error(id, "load_failed", "cannot put `" + site.file + "` back in the copy", true));
    return say(cast_done(id, c.wekufe, Outcome{"compile_error", "", diagnostic(project_->named(built.output))},
                         since(started)));
  }
  TestRun ran = project_->run_tests(tests, &cancel);
  bool failed = false;
  for (const auto& kv : ran.by_id) failed = failed || kv.second.status == TestResult::Status::Failed;
  // The rest is asked only when none of the tests the coverage chose
  // noticed the wekufe.
  if (!ran.cancelled && ran.broken.empty() && !failed && !rest.empty()) {
    TestRun more = project_->run_tests(rest, &cancel);
    ran.cancelled = more.cancelled;
    ran.broken = more.broken;
    for (const auto& kv : more.by_id) ran.by_id[kv.first] = kv.second;
    tests.insert(tests.end(), rest.begin(), rest.end());
  }
  bool restored = restore();
  if (ran.cancelled) return Reply{"", true, false};
  if (!restored) return say(error(id, "load_failed", "cannot put `" + site.file + "` back in the copy", true));
  if (!ran.broken.empty()) {
    return say(cast_done(id, c.wekufe, Outcome{"crashed", "", ran.broken}, since(started)));
  }
  // A test that failed is the kill. A test that ended by a signal or its
  // own timeout said nothing about the wekufe, so it is not one; a test
  // asked for that never ran is not one either.
  for (const auto* t : tests) {
    auto it = ran.by_id.find(t->id);
    if (it != ran.by_id.end() && it->second.status == TestResult::Status::Failed) {
      return say(cast_done(id, c.wekufe, Outcome{"killed", t->id, ""}, since(started)));
    }
  }
  for (const auto* t : tests) {
    auto it = ran.by_id.find(t->id);
    if (it != ran.by_id.end() && it->second.status == TestResult::Status::Crashed) {
      return say(cast_done(id, c.wekufe, Outcome{"crashed", "", t->id + ": " + it->second.message}, since(started)));
    }
  }
  int missing = 0;
  for (const auto* t : tests) {
    auto it = ran.by_id.find(t->id);
    if (it == ran.by_id.end() || it->second.status != TestResult::Status::Passed) missing++;
  }
  if (missing > 0) {
    return say(cast_done(id, c.wekufe,
                         Outcome{"crashed", "",
                                 std::to_string(tests.size() - static_cast<std::size_t>(missing)) + " of " +
                                     std::to_string(tests.size()) + " selected test(s) ran"},
                         since(started)));
  }
  return say(cast_done(id, c.wekufe, Outcome{"survived", "", ""}, since(started)));
}

std::string Service::reload(std::int64_t id, const std::vector<std::string>& files) {
  auto started = std::chrono::steady_clock::now();
  std::string why;
  if (!project_->copy_files(files, &why)) return error(id, "load_failed", why, true);
  // What the files now include is not known until the next build.
  compiled_.reset();
  return reloaded(id, files, since(started));
}

}  // namespace kalku
