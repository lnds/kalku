#include <stdio.h>
#include <string.h>

#include "calc.h"

int main(void) {
  int failed = 0;
  if (clamp(5, 0, 10) != 5) {
    puts("clamp(5, 0, 10) should be 5");
    failed++;
  }
  if (!positive(3)) {
    puts("3 should be positive");
    failed++;
  }
  if (strcmp(grade(95), "A") != 0) {
    puts("grade(95) should be A");
    failed++;
  }
  return failed == 0 ? 0 : 1;
}
