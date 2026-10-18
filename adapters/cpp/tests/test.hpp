// A test runner of a few lines, so the kalku's tests need nothing installed.
#pragma once

#include <functional>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

namespace kalku::testing {

struct Case {
  std::string name;
  std::function<void()> body;
};

std::vector<Case>& cases();
void failed(const std::string& where, const std::string& what);

struct Register {
  Register(const char* name, std::function<void()> body) { cases().push_back({name, std::move(body)}); }
};

// What a fixture is, relative to the kalku's own directory.
std::string here(const std::string& relative);
std::string read(const std::string& path);

}  // namespace kalku::testing

#define KALKU_CAT2(a, b) a##b
#define KALKU_CAT(a, b) KALKU_CAT2(a, b)
#define TEST(name)                                                                                \
  static void KALKU_CAT(test_, __LINE__)();                                                       \
  static ::kalku::testing::Register KALKU_CAT(reg_, __LINE__)(name, &KALKU_CAT(test_, __LINE__)); \
  static void KALKU_CAT(test_, __LINE__)()

#define CHECK(cond)                                                                            \
  do {                                                                                         \
    if (!(cond)) {                                                                             \
      ::kalku::testing::failed(std::string(__FILE__) + ":" + std::to_string(__LINE__), #cond); \
    }                                                                                          \
  } while (0)

#define CHECK_EQ(a, b)                                                                                  \
  do {                                                                                                  \
    auto&& kalku_a = (a);                                                                               \
    auto&& kalku_b = (b);                                                                               \
    if (!(kalku_a == kalku_b)) {                                                                        \
      std::ostringstream kalku_os;                                                                      \
      kalku_os << #a << " == " << #b << "\n    got:  " << kalku_a << "\n    want: " << kalku_b;         \
      ::kalku::testing::failed(std::string(__FILE__) + ":" + std::to_string(__LINE__), kalku_os.str()); \
    }                                                                                                   \
  } while (0)
