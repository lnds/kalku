cmake_minimum_required(VERSION 3.21)
project(optional C)

# Built only when asked for, as cJSON builds cJSON_Utils.c: off, no compile
# reads the file, and no test can run its code.
option(WITH_EXTRA "Build the extra part." OFF)

add_library(core src/core.c)
if(WITH_EXTRA)
  add_library(extra src/extra.c)
endif()

include(CTest)
if(BUILD_TESTING)
  add_executable(core_test tests/core_test.c)
  target_link_libraries(core_test PRIVATE core)
  add_test(NAME core COMMAND core_test)
endif()
