cmake_minimum_required(VERSION 3.21)
project(ring LANGUAGES C)

set(CMAKE_C_STANDARD 11)

add_library(ring src/ring.c)
target_include_directories(ring PUBLIC include)

# Unity is vendored as projects commonly do; its code is not the project's,
# and no wekufe is cast in it.
add_library(unity vendor/unity/unity.c)
target_include_directories(unity PUBLIC vendor/unity)

enable_testing()
foreach(name basics edges)
  add_executable(test_${name} test/test_${name}.c)
  target_link_libraries(test_${name} PRIVATE ring unity)
  add_test(NAME ${name} COMMAND test_${name})
endforeach()
