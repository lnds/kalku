# kalku-java

The Java kalku: it finds the places where a defect can be cast in a Java
project, casts them, and says which ones the project's tests notice.

It speaks [the kalku protocol](../../docs/protocol.md) and does what a kalku is
for: it knows the language. Planning, scheduling, caching and scoring belong to
the kaikai side. The design, and why this is not a wrapper around an existing
framework, is in [`docs/design.md`](../../docs/design.md).

## Using it

```sh
cd my-project          # where pom.xml or build.gradle is
kalku init             # writes .kalku.toml and .kalku/summon
kalku run src/main/java/com/acme/Parser.java
```

`kalku-java` has to be on the `PATH` (it ships in the release tarball and in
`brew install lnds/kalku/kalku`). It is one file: the script that picks the
JDK, with the kalku's jar behind it.

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
  project has, from 5.4 to 6, or against the one fetched for a project on
  JUnit 4 or TestNG.

- **The tests are the build's.** The classes that are tests are the ones the
  build takes: surefire's `includes` and `excludes`, the `include` and
  `exclude` patterns of Gradle's `test` task, or what each takes when a build
  names none. A pattern in a form the kalku does not read is refused by name,
  never guessed at. The variables a build sets in the environment of its tests
  are set, and a test surefire would run again (`rerunFailingTestsCount`) is
  run again: it has failed only when it fails every time. What is still not
  read, the baseline says in `differences`.

- **`abort`** ends the JVM the tests run in and everything it started; the
  kalku stays up for the next cast.

- **Coverage per test**, counted by JaCoCo (fetched by Maven into the reni) in
  a second run of the suite, and read afterwards in a JVM of its own so that
  its bytecode library is never on the class path of the project's tests. A
  wekufe is then cast only against the tests that reach it. It is withheld,
  and the whole suite used, whenever it cannot be trusted.

- **Several modules**: Maven runs once at the top; each module's tests run in a
  JVM of its own; and a site is judged by its module's tests and by those of
  every module that uses it. A test is then named with its module,
  `core::com.acme.PricesTest#rounds()`.

- **Gradle**: the build is asked by running it, with the project's own wrapper
  and a script added from outside the project; without its daemon. A build
  whose tests run on another JDK through a toolchain is refused by name.

- **JUnit 4 and TestNG**: a project that tests with either, and has nothing of
  the JUnit Platform, gets the engine that runs its framework on it fetched
  into the reni; one that tests with both gets both. JUnit 4.12 or later.

## Requirements

- **Java 11 or newer**, and a JDK, not a JRE: sources are read, and wekufe
  compiled, with the compiler that ships in it. The kalku runs on the project's own JDK —
  `KALKU_JAVA`, then `JAVA_HOME`, then `java` on the `PATH`.
- **Maven or Gradle**, whichever builds the project: its own wrapper (`mvnw`,
  `gradlew`), or `mvn` / `gradle` on the `PATH`, or `KALKU_MAVEN` /
  `KALKU_GRADLE`. It fetches the project's dependencies, the JUnit launcher and
  the coverage agent.
- No dependencies. The suite uses JUnit; the kalku itself uses the JDK and
  nothing else.

It is tested on Java 11, 17, 21 and 25, and the sites it finds in a file are
the same on each of them. A fixture that uses newer syntax is read from the
release that brought it.

## Running it

```sh
mvn verify                                  # compile with -Xlint:all -Werror, test, build the jar
bin/kalku-java                              # the protocol loop, from what Maven compiled last
make -C ../.. adapters/java/dist/kalku-java   # the one file a release ships
```

`bin/kalku-java` is the summoning command. It reads the Java version before it
starts anything, because a jar built for Java 11 does not load on Java 8, and a
class-version error on a closed pipe tells nobody what to do.

## Fixtures

`src/test/projects/` holds whole Maven projects written so that the outcome of
each wekufe is known: `calc`; `generated`, part of whose code an annotation
processor writes; `tables`, whose class fills a table when it is first used;
and `shop`, three modules of which one uses another and one is nothing but
tests. `junit4` and `testng` are `calc` under tests written for those,
`suite` is `calc` under a build that says which classes are its tests and what
they run with, and `gradle/` holds Gradle builds of the same sources.

`src/test/fixtures/<spell>/` holds sources written a particular way on purpose,
each beside the exact sites it must give (`*.sites.ndjson`). After reviewing a
change to them:

```sh
KALKU_UPDATE_GOLDENS=1 mvn test
```
