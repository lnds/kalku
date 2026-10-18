#include "ring.h"

void ring_init(ring *r) {
  r->head = 0;
  r->count = 0;
}

bool ring_full(const ring *r) { return r->count == RING_CAPACITY; }

bool ring_push(ring *r, int item) {
  if (ring_full(r)) return false;
  r->items[(r->head + r->count) % RING_CAPACITY] = item;
  r->count++;
  return true;
}

bool ring_pop(ring *r, int *out) {
  if (r->count == 0 || out == NULL) return false;
  *out = r->items[r->head];
  r->head = (r->head + 1) % RING_CAPACITY;
  r->count--;
  return true;
}
