#include <stdbool.h>

/* `&&` and `||` are sites; `&` and `|` are arithmetic. */
bool either(bool a, bool b) { return a || b; }

bool both(int x, int y) { return x > 0 && y > 0; }

unsigned mask(unsigned flags) { return (flags & 1u) | 2u; }

int pick(int x, int y, int z) {
  if (x && (y || z)) return 1;
  return 0;
}
