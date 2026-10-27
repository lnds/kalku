# kalku-cpp

The C++ kalku: it finds the places where a defect can be cast in a CMake
project, casts them, and says which ones the project's tests notice.

It speaks [the kalku protocol](../../docs/protocol.md) and does what a kalku is
for: it knows the language. Planning, scheduling, caching and scoring belong to
the kaikai side. The design is in [`docs/design.md`](../../docs/design.md).

## Using it

```sh
cd my-project          # where the top CMakeLists.txt is
kalku init             # writes .kalku.toml and .kalku/summon
kalku run src/parser.cpp include/acme/ring.hpp
```

`kalku-cpp` has to be on the `PATH` (it ships in the release tarball and in
`brew install lnds/kalku/kalku`). It runs the `cmake` and `ctest` on the
`PATH` (CMake 3.21 or later), and reads C++ with the machine's libclang: the
one beside the `clang` on the `PATH`, Xcode's, or a distribution's
(`libclang1-18` on Ubuntu). `KALKU_LIBCLANG` names another.

Options for cmake go under `[cpp]` in `.kalku.toml`:

```toml
[cpp]
cmake_args = ["-DCMAKE_BUILD_TYPE=Debug", "-DACME_BUILD_TESTS=ON"]
```

## What it does

- **Sites** from Clang's own parse of each file, with the flags the build's
  `compile_commands.json` gives it: `compare`, `connect`, `negate` (the
  condition of an `if`, a loop or `?:`, and a `!` dropped), `literal` (decimal
  integers, booleans, non-empty strings), `arm` (a clause of a `switch`, never
  `default` or the last), `call` (a call that is a whole statement becomes
  `{}`) and, where `[run] spells` names it, `return` (a returned value becomes
  its type's neutral one: `0`, `0.0`, `false`, `nullptr`, or `{}` for a
  standard string, vector or optional). Headers are read too, with the flags of a file beside them, and a
  wekufe in one rebuilds every file that includes it. Test files, anything a
  macro writes or is handed, what is never evaluated (`sizeof`,
  `static_assert`, `if constexpr`), template arguments, enum values, array
  sizes, `case` labels, calls to operators, constructors and the functions
  that end a path, and the calls `exclude_calls` names are never touched.
- **Prepares** a copy of the project in the reni — everything but `.git` and
  any directory CMake built into — configured into a build tree of its own
  with `BUILD_TESTING=ON`, and built. Ninja is used when it is on the `PATH`
  and nobody chose a generator. The project is built once, in a base no
  wekufe is ever written into, and each worker's copy and build tree are
  copied from it with their dates, so no worker compiles it again; what the
  base linked that names the base's tree is linked again in the worker's.
  Under Ninja its logs are rewritten for the copy too: each command's hash,
  and the path of each header an object read.
- **Casts** by writing the wekufe into the copy, rebuilding, running the
  selected tests through CTest, and putting the file back before it answers.
  A build that fails is `compile_error`, with what the compiler said. A
  wekufe in a shared library relinks the library alone: the tests load it
  from the build tree when they run (`CMAKE_LINK_DEPENDS_NO_SHARED`), unless
  the build puts a copy of it beside a test each time the test is linked.
- **Counts each test's own verdict** from CTest's JUnit report. A test that
  fails is the kill. One that ends by a signal or by its own `TIMEOUT` said
  nothing about the wekufe, and is `crashed`; so is a run in which a test
  asked for did not run.
- **Coverage per test**: after the baseline, the project is built a second
  time with Clang's source-based coverage, each test is run alone with a
  profile of its own, and `llvm-cov` says which lines it ran, so a wekufe
  faces only the tests that reach it. A line no test was seen to run is
  credited to every test. Under GCC, or where no `llvm-profdata` and
  `llvm-cov` of the compiler's own release are found, there is no coverage,
  and the baseline says why; so does a test that passes in the suite and
  fails alone.
- **A baseline runs only what changed.** Each test, or each case of a
  Catch2 or doctest executable, is fingerprinted by what its executable is
  built from, as CMake's file API, `compile_commands.json` and the
  compiler's depfiles (Ninja's deps log under Ninja) say it: the source and
  every header of each object of the executable and of every library it
  links, the compile commands, and how each target is linked; with its CTest
  entry, the files under its working directory the build did not write,
  every file of the project no compile reads, the toolchain's versions and
  the kalku's. A test offered back with the fingerprint it has now is not
  run again. A test whose executable is not a target of the build, whose
  working directory is outside the project, or that needs a CTest fixture
  has no fingerprint, and always runs.
- **`abort`** ends the build's or the run's process group, puts the file
  back, and the kalku takes the next request.

## Limits worth knowing

- A test is not run again for a change to a file a compile reads, unless
  its own executable is built from that file or the file is under its
  working directory: a test that reads a source of another target at run
  time, or runs or loads by a path of its own making a target it neither
  links nor names in its command (a module library in its working directory
  excepted), is not run again when only those change. `kalku run --all` runs
  every test.
- A CTest test is the unit: a test executable registered as one test is run
  whole. GoogleTest's `gtest_discover_tests`, Catch2's `catch_discover_tests`
  and doctest's `doctest_discover_tests` register each case, and then a case
  is the unit.
- A Catch2 (v2 or v3) or doctest executable that CTest runs whole, with no
  arguments, is asked for its cases, and each case is a test, named
  `<ctest test>/<case>`: the kalku runs the cases it is asked for by name,
  from the executable, and reads each one's verdict from the framework's XML
  report. When it cannot list them it runs the executable whole and says why
  in the baseline's differences.
- Under Ninja an output whose command reads a response file is built again
  in each worker, and a Ninja whose log is newer than version 7 builds each
  worker's copy whole.
- A build that turns warnings into errors (`-Werror`) makes a wekufe that
  leaves a variable unused a compile error, not a measurement.
- Under make, which dates what it builds to the second, a cast waits for the
  clock to pass the second its last build ended in; Ninja does not.
- On macOS the first run of every freshly linked executable or library
  waits for the system's Gatekeeper scan, one file at a time across the
  machine: about 0.15 to 0.3 s each, so a cast that relinks twenty test
  executables spends seconds before they run, and more workers barely help.
  A terminal listed under System Settings → Privacy & Security → Developer
  Tools is exempt; that is the machine owner's choice, and kalku does not
  make it.

## Developing it

```sh
make test-cpp                                      # builds and runs everything
UPDATE_GOLDEN=1 adapters/cpp/build/kalku-cpp-tests golden   # then read the diff
```

The code is formatted with clang-format 18 (`.clang-format`), and built with
every warning an error.
