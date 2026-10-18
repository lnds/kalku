cmake_minimum_required(VERSION 3.21)
project(range CXX)

set(CMAKE_CXX_STANDARD 17)

add_library(range src/range.cpp)
target_include_directories(range PUBLIC include)

# Catch2 is vendored as projects commonly do; its code is not the project's,
# and no wekufe is cast in it.
add_library(catch2 STATIC vendor/catch2/catch_amalgamated.cpp)
target_include_directories(catch2 PUBLIC vendor/catch2)

enable_testing()
add_executable(range_test tests/range_test.cpp)
target_link_libraries(range_test PRIVATE range catch2)
# The executable is one CTest test, as cxxopts registers its own; the case
# that kills comes after one that passes.
add_test(NAME range COMMAND range_test)
# A case CTest already has, as `catch_discover_tests` registers each one.
add_test(NAME "range edges" COMMAND range_test "the edges of a range")
