#ifndef RING_H
#define RING_H

#include <stdbool.h>
#include <stddef.h>

/* Code in a header is a site like any other; a wekufe in it is compiled into
   every unit that includes it. Declarations are not. */
struct ring {
  int items[4];
  size_t count;
};

bool ring_push(struct ring *r, int item);

static inline bool ring_full(const struct ring *r) { return r->count == 4; }

static inline bool ring_empty(const struct ring *r) { return r->count == 0 || r == NULL; }

#endif
