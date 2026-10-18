// Sites, from Clang's own parse of a file and never from a scan of its
// text: a comment, the contents of a string, a macro's expansion is never a
// site.
#pragma once

#include <optional>
#include <string>
#include <vector>

#include "clang.hpp"
#include "protocol.hpp"

namespace kalku {

struct Searched {
  std::vector<Site> sites;
  std::optional<Skipped> skipped;
};

struct SiteSearch {
  // The file as the protocol names it, relative to the project's root.
  std::string file;
  // The path Clang is told it is at, which its compile command names.
  std::string path;
  // What it holds: parsed as it is, whatever is on the disk under `path`.
  std::string text;
  // The command that compiles it, or one that compiles a file beside it.
  std::vector<std::string> args;
  std::string directory;
  // A header: parsed on its own, and a wekufe in it recompiles its includers.
  bool header = false;
  // Read as C rather than C++: what a header is read as, which its name
  // does not say.
  bool c = false;
  std::vector<std::string> spells;
  std::vector<std::string> exclude_calls;
};

Searched find_sites(const cx::Api& api, const SiteSearch& s);

}  // namespace kalku
