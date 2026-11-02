// Where a file invokes macros, as offsets, asked of every node of its AST:
// sorted by start, each with the furthest end any span starting no later
// reaches, so that each question is a binary search.
#pragma once

#include <utility>
#include <vector>

namespace kalku {

class Spans {
 public:
  explicit Spans(std::vector<std::pair<long, long>> spans);

  // One span holds [from, to) whole.
  bool holds(long from, long to) const;
  // [from, to) overlaps a span it does not hold: it begins or ends inside it.
  bool crosses(long from, long to) const;

 private:
  std::vector<long> starts_;
  // The furthest end among the spans up to and including each.
  std::vector<long> reach_;
  // Whether a span that starts before `at` ends after `past`.
  bool reaches(long at, long past) const;
};

}  // namespace kalku
