#ifndef CALC_H
#define CALC_H

#include <stdbool.h>

int clamp(int v, int lo, int hi);
int first_or(const int *p, int fallback);
int sign(int x);
bool in_range(int v, int lo, int hi);
const char *grade(int score);
int larger(int a, int b);
int digits(int n);

/* Defined here, so a wekufe in it is compiled into every unit that
   includes this header. */
static inline bool positive(int x) { return x > 0; }

#endif
