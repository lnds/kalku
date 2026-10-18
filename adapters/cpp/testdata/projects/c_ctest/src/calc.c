#include "calc.h"

#include <stddef.h>

#define MAX(a, b) ((a) > (b) ? (a) : (b))

static int calls = 0;

static void count(void) { calls++; }

int clamp(int v, int lo, int hi) {
  if (v < lo) return lo;
  if (v > hi) return hi;
  return v;
}

int first_or(const int *p, int fallback) {
  if (p == NULL) return fallback;
  return *p;
}

int sign(int x) {
  count();
  if (x < 0) return -1;
  return x > 0 ? 1 : 0;
}

bool in_range(int v, int lo, int hi) { return v >= lo && v <= hi; }

const char *grade(int score) {
  switch (score / 10) {
    case 10:
    case 9:
      return "A";
    case 8:
      return "B";
    default:
      return "C";
  }
}

int digits(int n) {
  int places = 1;
  while (n >= 10) {
    n /= 10;
    places++;
  }
  return places;
}

/* The comparison inside MAX belongs to the macro, not to this line. */
int larger(int a, int b) { return MAX(a, b); }
