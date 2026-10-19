#include "range.hpp"

namespace range {

int clamp(int v, int lo, int hi) {
  if (v < lo) return lo;
  if (v > hi) return hi;
  return v;
}

int sign(int x) {
  if (x < 0) return -1;
  return x > 0 ? 1 : 0;
}

int first_or(const int* p, int fallback) {
  if (p == nullptr) return fallback;
  return *p;
}

}  // namespace range
