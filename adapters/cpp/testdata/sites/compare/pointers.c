#include <stdbool.h>
#include <string.h>

/* Every comparison is a site, pointers included. */
bool inside(int a, int lo, int hi) { return a >= lo && a < hi; }

bool same(const char *x, const char *y) { return strcmp(x, y) == 0; }

int clamp(int v, int top) {
  if (v > top) return top;
  if (v <= 0) return 0;
  return v != 7 ? v : 8;
}

const char *first(const char *s) { return s != NULL ? s : ""; }
