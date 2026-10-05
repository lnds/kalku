# kalku-java

The Java kalku: it finds the places where a defect can be cast in a Java
project. It does not cast them yet.

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
- **`prepare`, `baseline` and `cast`** are answered with `not_implemented`.
  Nothing launches this kalku yet: `kalku init` does not know Maven or Gradle
  projects.

## Requirements

- **Java 11 or newer**, and a JDK, not a JRE: sources are read with the
  compiler that ships in it. The kalku runs on the project's own JDK —
  `KALKU_JAVA`, then `JAVA_HOME`, then `java` on the `PATH`.
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

`src/test/fixtures/<spell>/` holds sources written a particular way on purpose,
each beside the exact sites it must give (`*.sites.ndjson`). After reviewing a
change to them:

```sh
KALKU_UPDATE_GOLDENS=1 mvn test
```
