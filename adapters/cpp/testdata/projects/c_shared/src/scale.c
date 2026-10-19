#include "scale.h"

int scale(int v, int factor) {
  if (factor < 1) return 0;
  return v * factor;
}
