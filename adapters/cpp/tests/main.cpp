#include <cstdlib>
#include <fstream>

#include "test.hpp"

namespace kalku::testing {

std::vector<Case>& cases() {
  static std::vector<Case> all;
  return all;
}

namespace {
int failures = 0;
}

void failed(const std::string& where, const std::string& what) {
  failures++;
  std::cerr << "  FAIL " << where << ": " << what << "\n";
}

std::string here(const std::string& relative) { return std::string(KALKU_CPP_DIR) + "/" + relative; }

std::string read(const std::string& path) {
  std::ifstream in(path, std::ios::binary);
  std::ostringstream s;
  s << in.rdbuf();
  return s.str();
}

}  // namespace kalku::testing

// Runs every test, or those whose names hold the argument.
int main(int argc, char** argv) {
  using namespace kalku::testing;
  std::string only = argc > 1 ? argv[1] : "";
  int ran = 0;
  for (const auto& c : cases()) {
    if (!only.empty() && c.name.find(only) == std::string::npos) continue;
    int before = failures;
    c.body();
    ran++;
    std::cerr << (failures == before ? "ok   " : "FAIL ") << c.name << "\n";
  }
  std::cerr << ran << " test(s), " << failures << " failure(s)\n";
  return failures == 0 && ran > 0 ? 0 : 1;
}
