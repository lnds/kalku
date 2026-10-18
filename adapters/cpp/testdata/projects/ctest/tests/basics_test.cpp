#include <cstdio>

#include "calc.hpp"

// Only the middle of each range: a wekufe at an edge passes here.
int main() {
  int failed = 0;
  if (calc::clamp(5, 0, 10) != 5) {
    std::puts("clamp(5, 0, 10) should be 5");
    failed++;
  }
  if (!calc::positive(3)) {
    std::puts("3 should be positive");
    failed++;
  }
  if (calc::sign(7) != 1) {
    std::puts("sign(7) should be 1");
    failed++;
  }
  if (calc::halvings(8) != 3) {
    std::puts("halvings(8) should be 3");
    failed++;
  }
  return failed == 0 ? 0 : 1;
}
