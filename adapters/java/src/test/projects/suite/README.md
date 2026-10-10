`calc`, under a build that says which classes are its tests and what they run with. The
sources are `calc`'s own: a test copies them and puts this `pom.xml` and these tests in place
of `calc`'s.

- `LabelCheck` is not named the way a test class is by default, and the build includes it. It
  is the only test that looks at the label of a number that is not zero.
- `BrokenTest` is named the way a test class is, always fails, and the build excludes it.
- `CalcTest` reads a variable the build sets in the environment of its tests.
- `FlakyTest` fails the first time it runs in a JVM and passes the second; the build runs a
  failed test again. Gradle has no such setting of its own, so its build leaves the class out.
