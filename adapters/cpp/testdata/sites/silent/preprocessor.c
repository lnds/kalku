#include <assert.h>

/* What a macro writes is not a site, nor is what is handed to one. */
#define ABOVE(x, y) ((x) > (y))
#define CHECKED(e) ((e) ? 1 : 0)

int pick(int a, int b) {
  assert(a >= 0);
  if (ABOVE(a, b)) return a;
  int s = CHECKED(a == b);
  /* A comment with a < b in it, and a string "with a < b", are not sites. */
  const char *note = "a < b";
  (void)note;
  return s + (int)sizeof(a < b);
}

/* A macro beside hand-written code leaves that code its sites. */
#define CAP 9
int small(int x) { return x < CAP; }
