cmake_minimum_required(VERSION 3.21)
project(traced CXX)

set(CMAKE_CXX_STANDARD 17)

add_library(alpha src/alpha.cpp)
target_include_directories(alpha PUBLIC include)
add_library(beta src/beta.cpp)
target_include_directories(beta PUBLIC include)

include(CTest)
if(BUILD_TESTING)
  add_executable(alpha_test tests/alpha_test.cpp)
  target_link_libraries(alpha_test PRIVATE alpha)
  add_executable(beta_test tests/beta_test.cpp)
  target_link_libraries(beta_test PRIVATE beta)
  add_executable(data_test tests/data_test.cpp)
  add_test(NAME alpha COMMAND alpha_test)
  add_test(NAME beta COMMAND beta_test)
  # Reads the file it checks from its working directory.
  add_test(NAME data COMMAND data_test WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/tests/data)
  # What a script runs, and what a test reads from outside the project,
  # cannot be traced.
  add_test(NAME scripted COMMAND ${CMAKE_COMMAND} -E echo traced)
  add_test(NAME outside COMMAND alpha_test WORKING_DIRECTORY /)
endif()
