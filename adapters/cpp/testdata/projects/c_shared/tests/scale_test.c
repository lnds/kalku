#include "scale.h"

#include <stdio.h>

int main(void) {
  if (scale(3, 1) != 3) {
    puts("scale(3, 1) should be 3");
    return 1;
  }
  return 0;
}
