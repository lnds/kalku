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
  `default` or the last) and `call` (a call that is a whole statement becomes
  `{}`). Headers are read too, with the flags of a file beside them, and a
  wekufe in one rebuilds every file that includes it. Test files, anything a
  macro writes or is handed, what is never evaluated (`sizeof`,
  `static_assert`, `if constexpr`), template arguments, enum values, array
  sizes, `case` labels, calls to operators, constructors and the functions
  that end a path, and the calls `exclude_calls` names are never touched.
- **Prepares** a copy of the project in the reni — everything but `.git` and
  any directory CMake built into — configured into a build tree of its own
  with `BUILD_TESTING=ON`, and built. Ninja is used when it is on the `PATH`
  and nobody chose a generator. Under make the project is built once, in a
  base no wekufe is ever written into, and each worker's copy and build tree
  are copied from it with their dates, so no worker compiles it again; what
  the base linked that names the base's tree is linked again in the worker's.
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
- **`abort`** ends the build's or the run's process group, puts the file
  back, and the kalku takes the next request.

## Limits worth knowing

- No baseline that runs only what changed yet: every baseline runs every test.
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
- Under Ninja each worker builds the whole project in `prepare`, in its own
  build tree: Ninja keeps the commands it ran in logs of its own, and a copy
  of its build tree names other paths, so it would build it all again.
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
