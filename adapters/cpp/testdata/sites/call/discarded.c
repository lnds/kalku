#include <stdio.h>
#include <stdlib.h>

struct store {
  int items[8];
  int count;
};

static void add(struct store *s, int x) { s->items[s->count++] = x; }

static void reset(struct store *s) { s->count = 0; }

/* A call whose value is thrown away is a site; one whose value is used is
   not, and neither is a call that ends the program. */
int run(struct store *s, int fail) {
  add(s, 1);
  if (fail) abort();
  if (s->count == 0) reset(s);
  int used = s->count;
  printf("%d\n", used);
  return used;
}
