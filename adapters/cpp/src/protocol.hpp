// The kalku protocol as this kalku hears and speaks it (docs/protocol.md).
#pragma once

#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "json.hpp"
#include "source.hpp"

namespace kalku {

constexpr int PROTOCOL = 1;
constexpr std::size_t MAX_LINE = 4 * 1024 * 1024;

// What this kalku can cast. `await` and `supervise` are for runtimes with
// supervised processes, and `foreign` is a driver's.
extern const std::vector<std::string> SPELLS;

bool is_spell(const std::string& name);

struct DecodeError {
  std::string kind;
  std::string detail;
  std::optional<std::int64_t> id;
};

struct Hello {
  std::int64_t protocol = 0;
  std::string root;
  std::string reni;
  std::int64_t worker = 0;
  std::int64_t inline_limit_bytes = 0;
  std::map<std::string, std::string> env;
  std::optional<Json> options;
};

struct Site {
  std::string site_id;
  std::string file;
  std::string enclosing;
  int ordinal = 0;
  Position start;
  std::optional<Position> end;
  std::string spell;
  std::optional<std::string> original;
  std::string replacement;
  std::string reload = "module";

  std::string json() const;
};

struct SitesRequest {
  std::vector<std::string> files;
  std::vector<std::string> spells;
  std::vector<std::string> exclude_calls;
};

struct CastRequest {
  std::string wekufe;
  Site site;
  std::vector<std::string> tests;
  std::vector<std::string> rest;
};

struct Request {
  std::string type;
  std::int64_t id = 0;
  Hello hello;
  SitesRequest sites;
  CastRequest cast;
  std::int64_t abort = 0;
  std::vector<std::string> reload;
  std::map<std::string, std::string> reuse;
};

// Reads one line sent to a kalku, or says which protocol error it is.
std::optional<Request> decode(const std::string& line, DecodeError* error);

// ---- replies ---------------------------------------------------------------

std::string ready(std::int64_t id, const std::string& adapter, const std::string& runtime,
                  const std::vector<std::string>& capabilities);
std::string error(std::int64_t id, const std::string& code, const std::string& message, bool fatal);
std::string bye(std::int64_t id);
std::string prepared(std::int64_t id, std::int64_t duration_ms, std::int64_t modules);

struct Timed {
  std::string test;
  std::string file;
  std::int64_t duration_ms = 0;
  // What the test runs; empty when the kalku cannot say.
  std::string fingerprint;
};

struct Failure {
  std::string test;
  std::string message;
};

// What a baseline says of coverage: the entries inline, or the path of a
// packed file, or why there are none.
struct Covered {
  std::string entries;
  std::string packed_path;
  std::string withheld;
};

std::string baseline_done(std::int64_t id, std::int64_t duration_ms, const std::vector<Timed>& tests,
                          const std::vector<Failure>& failures, const std::vector<std::string>& differences,
                          const Covered& covered = {}, const std::vector<std::string>& reused = {});

struct Skipped {
  std::string file;
  std::string reason;
  std::string message;
};

std::string sites_found(std::int64_t id, const std::vector<Site>& sites, const std::vector<Skipped>& skipped);

struct Outcome {
  std::string kind;
  std::string killed_by;
  std::string message;
};

std::string cast_done(std::int64_t id, const std::string& wekufe, const Outcome& o, std::int64_t duration_ms);
std::string aborted(std::int64_t id, std::int64_t cast, bool restored);
std::string reset_done(std::int64_t id, bool clean, std::int64_t duration_ms);
std::string reloaded(std::int64_t id, const std::vector<std::string>& modules, std::int64_t duration_ms);

}  // namespace kalku
