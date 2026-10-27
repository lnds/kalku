#include <optional>
#include <string>
#include <vector>

#define LIMIT 10

enum class Mode { Fast, Slow };

struct Point {
  int x;
};

int count(const std::vector<int>& v) { return static_cast<int>(v.size()); }
unsigned long total(unsigned long a, unsigned long b) { return a + b; }
double ratio(double a, double b) { return a / b; }
bool positive(int x) { return x > 0; }
const char* name(bool loud) { return loud ? "LOUD" : "quiet"; }
std::string greet(const std::string& who) { return "hi " + who; }
std::vector<int> twice(int x) { return {x, x}; }
std::optional<int> first(const std::vector<int>& v) {
  if (v.empty()) return std::nullopt;
  return v.front();
}

// Already the neutral value, or one `literal` turns.
int none() { return 0; }
long none_long() { return 0L; }
double none_double() { return 0.0; }
const char* nowhere() { return nullptr; }
std::string empty() { return {}; }
bool yes() { return true; }
bool no() { return false; }
int limit() { return LIMIT; }

// No neutral value this kalku can write for these types.
Mode mode(bool fast) { return fast ? Mode::Fast : Mode::Slow; }
Point origin(int x) { return Point{x}; }
int& at(std::vector<int>& v, int i) { return v[static_cast<std::size_t>(i)]; }
void nothing(int& x) { x++; }

constexpr int squared(int x) { return x * x; }

template <typename T>
T same(T x) {
  return x;
}

int apply(int x) {
  auto plus = [](int y) { return y + 1; };
  return plus(x);
}
