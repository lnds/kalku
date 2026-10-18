#include <stdbool.h>

#define LIMIT 10

enum color { RED = 1, GREEN = 2 };

struct packet {
  unsigned flags : 3;
  char tag[4];
};

/* Array sizes, bit-field widths, case labels and enumerator values are not
   values a test sees; what a function returns or compares is. */
const char *label(int n, bool loud) {
  int buffer[8] = {0};
  static const int steps[3] = {1, 2, 3};
  if (n > 0x10 || n == 010) return loud ? "BIG" : "big";
  switch (n) {
    case 4:
      return "four";
    default:
      break;
  }
  unsigned long big = 99ul;
  bool on = true;
  char c = 'x';
  return n + steps[0] + buffer[0] + (int)big + on + c + (int)sizeof(int) > LIMIT ? "" : "small";
}
