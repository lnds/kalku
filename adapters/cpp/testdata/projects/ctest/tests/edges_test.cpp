#include <cstdio>

#include "calc.hpp"

int main() {
  int failed = 0;
  if (calc::clamp(-3, 0, 10) != 0) {
    std::puts("clamp(-3, 0, 10) should be 0");
    failed++;
  }
  if (calc::positive(0)) {
    std::puts("0 should not be positive");
    failed++;
  }
  if (calc::sign(-5) != -1) {
    std::puts("sign(-5) should be -1");
    failed++;
  }
  // A null pointer is asked for its fallback: a wekufe that reads through
  // it ends the test with a signal.
  if (calc::first_or(nullptr, 3) != 3) {
    std::puts("first_or(nullptr, 3) should be 3");
    failed++;
  }
  return failed == 0 ? 0 : 1;
}
