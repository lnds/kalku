cmake_minimum_required(VERSION 3.21)
project(calc CXX)

set(CMAKE_CXX_STANDARD 17)

add_library(calc src/calc.cpp)
target_include_directories(calc PUBLIC include)

include(CTest)
if(BUILD_TESTING)
  add_executable(basics_test tests/basics_test.cpp)
  target_link_libraries(basics_test PRIVATE calc)
  add_executable(edges_test tests/edges_test.cpp)
  target_link_libraries(edges_test PRIVATE calc)
  # The test that kills most wekufe is listed after one that passes with
  # them, so a kalku that read one verdict for the run would be wrong.
  add_test(NAME basics COMMAND basics_test)
  add_test(NAME edges COMMAND edges_test)
endif()
