#include <string>
#include <vector>

namespace geometry {

// Every built-in comparison is a site; an overloaded one is a call.
bool inside(int a, int lo, int hi) { return a >= lo && a < hi; }

bool same(const std::string& x, const std::string& y) { return x == y; }

int clamp(int v, int top) {
  if (v > top) return top;
  if (v <= 0) return 0;
  return v != 7 ? v : 8;
}

// A template's `<` and `>` are not comparisons.
std::vector<int> pair(int a, int b) { return std::vector<int>{a, b}; }

}  // namespace geometry
