// The text of one file, read for positions: the protocol counts bytes for
// offsets and characters for columns.
#pragma once

#include <string>
#include <vector>

namespace kalku {

struct Position {
  int line = 0;
  int col = 0;
  long byte = 0;
};

class Source {
 public:
  explicit Source(const std::string& text);
  Position position(long offset) const;

 private:
  const std::string& text_;
  std::vector<long> lines_;
};

// The text with [from, to) replaced.
std::string splice(const std::string& text, long from, long to, const std::string& replacement);

}  // namespace kalku
