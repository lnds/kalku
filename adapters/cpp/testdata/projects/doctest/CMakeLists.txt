cmake_minimum_required(VERSION 3.21)
project(range CXX)

set(CMAKE_CXX_STANDARD 17)

add_library(range src/range.cpp)
target_include_directories(range PUBLIC include)

enable_testing()
add_executable(range_test tests/range_test.cpp)
target_link_libraries(range_test PRIVATE range)
# doctest is vendored as projects commonly do; its code is not the
# project's, and no wekufe is cast in it.
target_include_directories(range_test PRIVATE vendor/doctest)
# The executable is one CTest test; the case that kills comes after one
# that passes.
add_test(NAME range COMMAND range_test)
