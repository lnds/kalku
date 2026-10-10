A project whose tests are in two directories. `src/test/java` holds `NameTest`, which looks
at nothing a wekufe changes; `src/checks/java`, which the build adds, holds `QuotaTest`, the
only test that reads the constant `CAP` and the only one that calls `spent`. The constant is
copied into `QuotaTest` when it is compiled, so a wekufe in it is only noticed once that
directory is compiled again.

Maven adds the second directory with `build-helper-maven-plugin`; the Gradle build in
`../gradle/checks` gives its source set both.
