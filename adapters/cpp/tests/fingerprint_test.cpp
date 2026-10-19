#include "fingerprint.hpp"

#include "test.hpp"

using namespace kalku;

TEST("a depfile names the inputs of its first target, escapes undone") {
  auto in = depfile_inputs(
      "CMakeFiles/calc.dir/src/calc.cpp.o: /p/src/calc.cpp \\\n  /p/include/calc.hpp /p/my\\ dir/a.h \\\n"
      "  /p/cost$$.h\n/p/include/calc.hpp:\n");
  CHECK_EQ(in.size(), std::size_t(4));
  if (in.size() == 4) {
    CHECK_EQ(in[0], std::string("/p/src/calc.cpp"));
    CHECK_EQ(in[1], std::string("/p/include/calc.hpp"));
    CHECK_EQ(in[2], std::string("/p/my dir/a.h"));
    CHECK_EQ(in[3], std::string("/p/cost$.h"));
  }
  CHECK(depfile_inputs("").empty());
  CHECK(depfile_inputs("a.o:\n").empty());
}

TEST("ninja's deps log gives each output its inputs, and none for a stale record") {
  auto deps = ninja_deps(
      "CMakeFiles/calc.dir/src/calc.cpp.o: #deps 2, deps mtime 1792418918978520818 (VALID)\n"
      "    /p/src/calc.cpp\n"
      "    /p/include/calc.hpp\n"
      "\n"
      "CMakeFiles/old.dir/old.cpp.o: #deps 1, deps mtime 1 (STALE)\n"
      "    /p/old.cpp\n"
      "\n");
  CHECK_EQ(deps.size(), std::size_t(1));
  CHECK_EQ(deps["CMakeFiles/calc.dir/src/calc.cpp.o"].size(), std::size_t(2));
  CHECK_EQ(deps["CMakeFiles/calc.dir/src/calc.cpp.o"][1], std::string("/p/include/calc.hpp"));
  CHECK(deps.count("CMakeFiles/old.dir/old.cpp.o") == 0);
}
