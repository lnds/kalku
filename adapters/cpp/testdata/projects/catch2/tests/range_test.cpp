#include "catch_amalgamated.hpp"
#include "range.hpp"

// Only the middle of each range: a wekufe at an edge passes here.
TEST_CASE("the middle of a range") {
  CHECK(range::clamp(5, 0, 10) == 5);
  CHECK(range::sign(7) == 1);
}

// A name Catch2 would read as a list, a tag and a negation, were it not
// asked for as it is.
TEST_CASE("~a name, with [brackets] and a *") { CHECK(range::sign(0) == 0); }

TEST_CASE("the edges of a range") {
  CHECK(range::clamp(-3, 0, 10) == 0);
  CHECK(range::sign(-5) == -1);
  // A wekufe that reads through the null pointer ends the run by a signal.
  CHECK(range::first_or(nullptr, 3) == 3);
}
