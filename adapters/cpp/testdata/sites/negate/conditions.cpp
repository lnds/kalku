#include <memory>

int count(const int* xs, int n) {
  int total = 0;
  for (int i = 0; i < n; i++) {
    if (xs[i] > 0) total++;
  }
  while (total > 100) total -= 100;
  do {
    total++;
  } while (total < 3);
  return !n ? 0 : total;
}

int first(std::unique_ptr<int> p) {
  // A condition that declares a variable is left alone; one after an
  // init-statement is not.
  if (int* raw = p.get()) return *raw;
  if (int k = 3; k > 2) return k;
  for (;;) break;
  return 0;
}

template <typename T>
int kind() {
  // What `if constexpr` decides is decided while compiling.
  if constexpr (sizeof(T) > 4) return 8;
  return 4;
}

int use() { return kind<long>(); }
