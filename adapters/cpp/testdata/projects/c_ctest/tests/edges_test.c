#include <stddef.h>
#include <stdio.h>
#include <string.h>

#include "calc.h"

int main(void) {
  int failed = 0;
  if (clamp(-3, 0, 10) != 0) {
    puts("clamp(-3, 0, 10) should be 0");
    failed++;
  }
  if (clamp(12, 0, 10) != 10) {
    puts("clamp(12, 0, 10) should be 10");
    failed++;
  }
  if (positive(0)) {
    puts("0 should not be positive");
    failed++;
  }
  if (sign(-5) != -1 || sign(0) != 0) {
    puts("sign(-5) should be -1 and sign(0) 0");
    failed++;
  }
  if (!in_range(10, 0, 10) || in_range(11, 0, 10)) {
    puts("in_range should hold its ends and nothing past them");
    failed++;
  }
  if (strcmp(grade(85), "B") != 0) {
    puts("grade(85) should be B");
    failed++;
  }
  if (larger(2, 7) != 7) {
    puts("larger(2, 7) should be 7");
    failed++;
  }
  if (digits(5) != 1 || digits(123) != 3) {
    puts("digits(5) should be 1 and digits(123) 3");
    failed++;
  }
  /* A null pointer is asked for its fallback: a wekufe that reads through
     it ends the test with a signal. */
  if (first_or(NULL, 3) != 3) {
    puts("first_or(NULL, 3) should be 3");
    failed++;
  }
  return failed == 0 ? 0 : 1;
}
