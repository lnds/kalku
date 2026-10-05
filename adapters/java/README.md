# kalku-java

The Java kalku: it finds the places where a defect can be cast in a Java
project, casts them, and says which ones the project's tests notice.

It speaks [the kalku protocol](../../docs/protocol.md) and does what a kalku is
for: it knows the language. Planning, scheduling, caching and scoring belong to
the kaikai side. The design, and why this is not a wrapper around an existing
framework, is in [`docs/design.md`](../../docs/design.md).

## What it does

- **Sites** from `javac`, the project's own compiler, through its public tree
  API: `arm` (a `case` of a `switch`, while a `default` remains), `compare`,
  `connect`, `negate`, `literal` and `call`. Annotations, `case` labels,
  conditions that bind a pattern variable, the arguments of the calls
  `exclude_calls` names, and test sources are never touched.
- **Builds in the reni.** `prepare` copies the project there and lets Maven
  build the copy; the user's tree is never written to. It reads from Maven what
  the build tells `javac` and the tests' JVM.
- **Casts** by compiling the one changed file in memory with the project's own
  `javac`, and running the tests in a JVM of their own that is thrown away. A
  wekufe in a constant is compiled into every class and test that uses it.
- **A test is a method**, `com.acme.ParserTest#reads(int, String)`, run through
  the JUnit Platform. The runner is compiled in the reni against the JUnit the
  project has, from 5.4 to 6.

Not yet: several modules, Gradle, JUnit 4 or TestNG alone, `abort`, coverage
per test. Nothing launches this kalku yet: `kalku init` does not know Maven
projects.

## Requirements

- **Java 11 or newer**, and a JDK, not a JRE: sources are read, and wekufe
  compiled, with the compiler that ships in it. The kalku runs on the project's own JDK —
  `KALKU_JAVA`, then `JAVA_HOME`, then `java` on the `PATH`.
- **Maven**: the project's own wrapper (`mvnw`), or `mvn` on the `PATH`, or
  `KALKU_MAVEN`. It fetches the project's dependencies and the JUnit launcher.
- No dependencies. The suite uses JUnit; the kalku itself uses the JDK and
  nothing else.

It is tested on Java 11, 17, 21 and 25, and the sites it finds in a file are
the same on each of them. A fixture that uses newer syntax is read from the
release that brought it.

## Running it

```sh
mvn verify                 # compile with -Xlint:all -Werror, test, build the jar
bin/kalku-java             # the protocol loop on stdin and stdout
```

`bin/kalku-java` is the summoning command. It reads the Java version before it
starts anything, because a jar built for Java 11 does not load on Java 8, and a
class-version error on a closed pipe tells nobody what to do.

## Fixtures

`src/test/projects/` holds whole Maven projects written so that the outcome of
each wekufe is known: `calc`, and `generated`, part of whose code an annotation
processor writes.

`src/test/fixtures/<spell>/` holds sources written a particular way on purpose,
each beside the exact sites it must give (`*.sites.ndjson`). After reviewing a
change to them:

```sh
KALKU_UPDATE_GOLDENS=1 mvn test
```
