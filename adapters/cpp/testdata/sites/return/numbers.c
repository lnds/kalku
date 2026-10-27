#include <stdbool.h>
#include <stddef.h>

struct pair {
  int a;
  int b;
};

enum color { RED, GREEN };

int sum(int a, int b) { return a + b; }
unsigned char low(unsigned x) { return (unsigned char)(x & 0xFF); }
float half(float x) { return x / 2; }
bool even(int x) { return x % 2 == 0; }
const char* label(int x) { return x ? "one" : "other"; }
int* slot(int* xs, int i) { return xs + i; }

int zero(void) { return 0; }
unsigned zero_unsigned(void) { return 0u; }
int* nothing(void) { return NULL; }
bool always(void) { return true; }

struct pair make(int a) {
  struct pair p = {a, a};
  return p;
}
enum color pick(int x) { return x ? GREEN : RED; }
