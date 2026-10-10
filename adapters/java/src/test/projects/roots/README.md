A project whose code is in two directories. `src/main/java` holds `Limits`, a constant;
`src/shared/java`, which the build adds, holds `Gate`, the only class that uses it and the
only one a test looks at. `src/spare/java` is beside them and no build compiles it.

Maven adds the second directory with `build-helper-maven-plugin`; the Gradle build in
`../gradle/roots` gives its source set both.
