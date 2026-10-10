`calc`, tested with JUnit 4 and with TestNG, one test class each, and nothing of the JUnit
Platform. The sources are `calc`'s own: a test copies them and puts this `pom.xml` and these
tests in place of `calc`'s. Only the TestNG test looks at `label`, so a wekufe there is killed
only when the tests of both frameworks run.
