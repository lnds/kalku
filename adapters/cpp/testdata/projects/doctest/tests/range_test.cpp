#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <vector>

#include "doctest.h"
#include "range.hpp"

// Only the middle of each range: a wekufe at an edge passes here.
TEST_CASE("the middle of a range") {
  CHECK(range::clamp(5, 0, 10) == 5);
  CHECK(range::sign(7) == 1);
}

TEST_CASE("the edges of a range") {
  CHECK(range::clamp(-3, 0, 10) == 0);
  CHECK(range::sign(-5) == -1);
  // A wekufe that reads through the null pointer ends the run by a signal.
  CHECK(range::first_or(nullptr, 3) == 3);
}

// The second case reads what the first kept, so it passes in the suite and
// fails when it runs alone, whatever is cast.
static std::vector<int> kept;

TEST_CASE("a value past the end is kept") { kept.push_back(range::clamp(12, 0, 10)); }

TEST_CASE("the value kept is the end") {
  REQUIRE(kept.size() == 1);
  CHECK(kept[0] == 10);
}
