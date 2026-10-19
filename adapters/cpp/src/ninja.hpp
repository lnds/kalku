// What Ninja keeps of a build besides the files it built: `.ninja_log`, the
// command each output was built by, as a hash, and `.ninja_deps`, the
// headers each object was compiled from, by path. A copy of a build tree
// keeps both naming the paths of the tree it was copied from, so they are
// rewritten for the copy, and what cannot be is dropped and built again.
#pragma once

#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace kalku {

// Each path to replace, and what replaces it, in order.
using Rewrites = std::vector<std::pair<std::string, std::string>>;

// The hash a `.ninja_log` of `version` keys a command by: MurmurHash64A up
// to version 6, rapidhash from version 7. None for a version not known.
std::optional<std::uint64_t> ninja_hash(int version, const std::string& command);

// The version a `.ninja_log` says it is, or 0.
int ninja_log_version(const std::string& log);

// A `.ninja_log` whose every entry is keyed by the hash `rehash` gives its
// old one in place of it. An entry whose hash is not there is left out,
// and Ninja builds its output again; `dropped` counts them.
std::string rebase_ninja_log(const std::string& log, const std::map<std::uint64_t, std::uint64_t>& rehash,
                             int* dropped);

// A `.ninja_deps` whose paths are rewritten, or none when it is not one
// this knows, or a path in it still names `old_tree` after the rewrite.
std::optional<std::string> rebase_ninja_deps(const std::string& deps, const Rewrites& rw,
                                             const std::vector<std::string>& old_tree);

}  // namespace kalku
