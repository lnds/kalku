#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

namespace audit {
void log(const std::string&) {}
}  // namespace audit

struct Store {
  std::vector<int> items;
  void add(int x) { items.push_back(x); }
  void reset() {
    items.clear();
    audit::log("reset");
  }
};

int run(Store& s, bool fail) {
  s.add(1);
  if (fail) std::abort();
  if (s.items.empty()) s.reset();
  std::string message = "kept";
  message += "!";
  int used = static_cast<int>(s.items.size());
  std::printf("%d\n", used);
  return used;
}
