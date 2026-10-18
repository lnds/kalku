#include <stdbool.h>
#include <stddef.h>

/* A condition `c` becomes `!(c)`, and a `!` is dropped. */
int count_down(int n) {
  int steps = 0;
  while (n > 0) {
    n--;
    steps++;
  }
  for (int i = 0; i < 3; i++) steps++;
  do {
    steps--;
  } while (steps > 100);
  return steps;
}

bool missing(const int *p) { return !p; }

int choose(bool flag, int a, int b) {
  if (flag) return a;
  return flag ? a : b;
}
