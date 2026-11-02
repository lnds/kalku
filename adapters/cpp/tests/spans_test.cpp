// Macro spans, against every span looked at one by one.

#include "spans.hpp"

#include <utility>

#include "test.hpp"

using namespace kalku;

namespace {

using Pairs = std::vector<std::pair<long, long>>;

bool holds_by_scan(const Pairs& spans, long from, long to) {
  for (const auto& m : spans) {
    if (m.first <= from && to <= m.second) return true;
  }
  return false;
}

bool crosses_by_scan(const Pairs& spans, long from, long to) {
  for (const auto& m : spans) {
    bool overlaps = m.first < to && from < m.second;
    bool holds = from <= m.first && m.second <= to;
    if (overlaps && !holds) return true;
  }
  return false;
}

}  // namespace

TEST("a span is held by a macro that holds it whole, and crosses one it begins or ends inside") {
  Spans s({{10, 20}, {30, 40}});
  CHECK(s.holds(10, 20));
  CHECK(s.holds(12, 18));
  CHECK(!s.holds(5, 15));
  CHECK(!s.holds(20, 30));
  CHECK(s.crosses(5, 15));
  CHECK(s.crosses(15, 35));
  CHECK(!s.crosses(5, 45));
  CHECK(!s.crosses(10, 20));
  CHECK(!s.crosses(20, 30));
  CHECK(!Spans({}).holds(0, 1));
  CHECK(!Spans({}).crosses(0, 1));
}

TEST("the macro spans answer as a scan of every span does, nested, empty and backwards ones among them") {
  unsigned seed = 11;
  auto next = [&seed] {
    seed = seed * 1103515245u + 12345u;
    return static_cast<long>((seed >> 16) & 0x7fff);
  };
  for (int round = 0; round < 500; round++) {
    Pairs spans;
    long width = 5 + next() % 60;
    for (long n = next() % 12; n > 0; n--) {
      long a = next() % width, b = next() % width;
      if (next() % 8 != 0 && a > b) std::swap(a, b);
      spans.emplace_back(a, b);
    }
    Spans s(spans);
    for (long from = -1; from <= width + 1; from++) {
      for (long to = -1; to <= width + 1; to++) {
        CHECK_EQ(s.holds(from, to), holds_by_scan(spans, from, to));
        CHECK_EQ(s.crosses(from, to), crosses_by_scan(spans, from, to));
      }
    }
  }
}
