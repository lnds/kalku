#ifndef RING_H
#define RING_H

#include <stdbool.h>
#include <stddef.h>

#define RING_CAPACITY 4

typedef struct {
  int items[RING_CAPACITY];
  size_t head;
  size_t count;
} ring;

void ring_init(ring *r);
bool ring_push(ring *r, int item);
bool ring_pop(ring *r, int *out);
bool ring_full(const ring *r);

#endif
