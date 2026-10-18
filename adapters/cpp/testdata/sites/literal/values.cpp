#include <array>
#include <string>

constexpr int LIMIT = 10;
static_assert(LIMIT > 5, "a limit worth having");

enum class Color { Red = 1, Green = 2 };

struct Packet {
  unsigned flags : 3;
  int size = 64;
  char tag[4];
};

std::string label(int n, bool loud = false) {
  std::array<int, 3> steps{1, 2, 3};
  int buffer[8] = {};
  if (n > 0x10 || n == 010) return loud ? "BIG" : "big";
  switch (n) {
    case 4:
      return "four";
    default:
      break;
  }
  unsigned long big = 99ul;
  int sized(5);
  return std::to_string(n + steps[0] + buffer[0] + static_cast<int>(big) + sized + static_cast<int>(sizeof(int)) +
                        int(true)) +
         "";
}
