#include <stddef.h>

struct buffer {
  const char *content;
  size_t length;
  size_t offset;
};

/* A `0` that becomes a pointer is a null pointer, not a number; the same
   `0` beside it as a count is a value. A string stays a value wherever it
   decays to a pointer. */
const char *parse(const char *value, const char **end) {
  struct buffer b = {0, 0, 0};
  const char *rest = 0;
  if (end == 0) end = &rest;
  *end = value + 1;
  b.content = value ? value : "empty";
  return b.length == 0 ? b.content : 0;
}
