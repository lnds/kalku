cmake_minimum_required(VERSION 3.21)
project(kalku_cpp CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

add_library(kalku_cpp STATIC
  src/cases.cpp
  src/clang.cpp
  src/coverage.cpp
  src/fingerprint.cpp
  src/framing.cpp
  src/json.cpp
  src/ninja.cpp
  src/process.cpp
  src/project.cpp
  src/protocol.cpp
  src/serve.cpp
  src/service.cpp
  src/sha256.cpp
  src/sites.cpp
  src/source.cpp)
target_include_directories(kalku_cpp PUBLIC src)
target_compile_options(kalku_cpp PRIVATE -Wall -Wextra -Wpedantic -Werror)
target_link_libraries(kalku_cpp PUBLIC ${CMAKE_DL_LIBS})
find_package(Threads REQUIRED)
target_link_libraries(kalku_cpp PUBLIC Threads::Threads)

add_executable(kalku-cpp src/main.cpp)
target_compile_options(kalku-cpp PRIVATE -Wall -Wextra -Wpedantic -Werror)
target_link_libraries(kalku-cpp PRIVATE kalku_cpp)
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  # One binary for every Linux: nothing of the C++ runtime is asked of the
  # machine it runs on.
  target_link_options(kalku-cpp PRIVATE -static-libstdc++ -static-libgcc)
endif()

add_executable(kalku-cpp-tests
  tests/main.cpp
  tests/cases_test.cpp
  tests/coverage_test.cpp
  tests/ninja_test.cpp
  tests/fingerprint_test.cpp
  tests/protocol_test.cpp
  tests/sites_test.cpp
  tests/service_test.cpp
  tests/pipe_test.cpp)
target_compile_options(kalku-cpp-tests PRIVATE -Wall -Wextra -Wpedantic -Werror)
target_link_libraries(kalku-cpp-tests PRIVATE kalku_cpp)
target_compile_definitions(kalku-cpp-tests PRIVATE
  KALKU_CPP_DIR="${CMAKE_CURRENT_SOURCE_DIR}"
  KALKU_CPP_BIN="$<TARGET_FILE:kalku-cpp>")
add_dependencies(kalku-cpp-tests kalku-cpp)
