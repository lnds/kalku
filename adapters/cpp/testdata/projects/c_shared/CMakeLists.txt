cmake_minimum_required(VERSION 3.21)
project(scale C)

add_library(scale SHARED src/scale.c)
target_include_directories(scale PUBLIC include)

# A test that loads its own copy of the library, put beside it each time it
# is linked: a build that skipped the link would leave it an older copy.
option(COPY_LIBRARY "Test against a copy of the library beside the test" OFF)

include(CTest)
if(BUILD_TESTING)
  add_executable(scale_test tests/scale_test.c)
  target_link_libraries(scale_test PRIVATE scale)
  if(COPY_LIBRARY)
    set_target_properties(scale_test PROPERTIES RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
                                                BUILD_WITH_INSTALL_RPATH ON)
    if(APPLE)
      set_target_properties(scale_test PROPERTIES INSTALL_RPATH "@loader_path")
    else()
      set_target_properties(scale_test PROPERTIES INSTALL_RPATH "$ORIGIN")
    endif()
    add_custom_command(TARGET scale_test POST_BUILD
                       COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:scale> $<TARGET_FILE_DIR:scale_test>)
  endif()
  add_test(NAME scale COMMAND scale_test)
endif()
