// What a test runs, as a fingerprint: everything its executable is built
// from, as the build says it, and what it reads that is not code.
//
// A test's executable is a target of CMake's file API, and so is every
// library it links. Each target's objects come from `compile_commands.json`,
// and each object's inputs, its source and every header it includes, from
// the compiler's own record of them: the object's depfile under make,
// Ninja's deps log under Ninja. A test's fingerprint covers those inputs,
// the compile commands and what the file API says of each target, which is
// how it is compiled and linked; the test's CTest entry; the files under its
// working directory that the build did not write; every file of the project
// that no compile reads; and the versions of the toolchain and the kalku.
#pragma once

#include <map>
#include <string>
#include <vector>

namespace kalku {

// Asks CMake, before it configures `build`, to describe the build it
// generates there.
void ask_for_codemodel(const std::string& build);

// The inputs a Makefile-style depfile names for its first target, as
// written; empty when it names none.
std::vector<std::string> depfile_inputs(const std::string& text);

// What `ninja -t deps` says each output was built from, by the output as
// written. An output whose record is stale is left out.
std::map<std::string, std::vector<std::string>> ninja_deps(const std::string& text);

}  // namespace kalku
