package io.github.lnds.kalku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which class files a build's patterns take for tests, as surefire and Gradle each read them. */
class SuiteTest {
  private static final List<String> NONE = Collections.emptyList();

  private static Suite surefire(List<String> includes, List<String> excludes) throws Exception {
    return Suite.surefire(includes, excludes, "the project");
  }

  @Test
  void surefireTakesItsFourNamesWhenABuildNamesNone() throws Exception {
    Suite suite = surefire(NONE, NONE);
    assertTrue(suite.takes("a/b/TestThing.class"));
    assertTrue(suite.takes("a/b/ThingTest.class"));
    assertTrue(suite.takes("ThingTests.class"));
    assertTrue(suite.takes("a/ThingTestCase.class"));
    assertFalse(suite.takes("a/b/ThingIT.class"));
    assertFalse(suite.takes("a/b/Testing/Thing.class"));
    // A nested class is its outer class's to run.
    assertFalse(suite.takes("a/b/ThingTest$Inner.class"));
  }

  @Test
  void whatABuildIncludesTakesThePlaceOfTheFourNames() throws Exception {
    Suite suite = surefire(Arrays.asList("**/*IT.java"), NONE);
    assertTrue(suite.takes("a/b/ThingIT.class"));
    assertFalse(suite.takes("a/b/ThingTest.class"));
  }

  @Test
  void whatABuildExcludesTakesThePlaceOfLeavingNestedClassesOut() throws Exception {
    Suite suite = surefire(NONE, Arrays.asList("**/Slow*.java"));
    assertFalse(suite.takes("a/SlowTest.class"));
    assertTrue(suite.takes("a/FastTest.class"));
    assertTrue(suite.takes("a/Fast$InnerTest.class"));
  }

  // A class may be named as a source, as a class file, by its name alone or with its package,
  // and surefire looks for each in any directory.
  @Test
  void surefireReadsAClassNamedInAnyOfItsForms() throws Exception {
    for (String form :
        Arrays.asList(
            "**/ThingCheck.java", "a/b/ThingCheck.java", "b/ThingCheck.class", "ThingCheck",
            "a.b.ThingCheck", "a.b.*Check", "b.Thing?heck.class", "**/b/*", "Thing*")) {
      Suite suite = surefire(Arrays.asList(form), NONE);
      assertTrue(suite.takes("a/b/ThingCheck.class"), form);
      assertFalse(suite.takes("a/c/OtherTest.class"), form);
    }
    assertFalse(surefire(Arrays.asList("Thing"), NONE).takes("a/b/ThingCheck.class"));
    assertFalse(surefire(Arrays.asList("c/ThingCheck.java"), NONE).takes("a/b/ThingCheck.class"));
  }

  @Test
  void oneEntryCanHoldSeveralPatterns() throws Exception {
    Suite suite = surefire(Arrays.asList("**/*IT.java, **/*Check.java"), NONE);
    assertTrue(suite.takes("a/ThingIT.class"));
    assertTrue(suite.takes("a/ThingCheck.class"));
    assertFalse(suite.takes("a/ThingTest.class"));
  }

  @Test
  void aFormSurefireReadsByOtherRulesIsRefusedByName() {
    for (String form : Arrays.asList("%regex[.*Check.*]", "!**/Slow*.java", "ThingTest#fast*")) {
      Maven.Failed refused =
          assertThrows(Maven.Failed.class, () -> Suite.surefire(NONE, Arrays.asList(form), "the module `core`"));
      assertTrue(refused.getMessage().contains("`" + form + "`"), refused.getMessage());
      assertTrue(refused.getMessage().contains("surefire's `excludes` in the module `core`"));
    }
  }

  @Test
  void gradleTakesEveryClassWhenABuildNamesNone() {
    Suite suite = Suite.gradle(NONE, NONE);
    assertTrue(suite.takes("a/b/ThingCheck.class"));
    assertTrue(suite.takes("a/b/ThingTest$Inner.class"));
  }

  // Gradle holds a pattern against the class file as it is written: from the top of the
  // classes, and with the file's extension.
  @Test
  void gradleReadsAPatternAsItIsWritten() {
    Suite suite = Suite.gradle(Arrays.asList("**/*IT.class", "a/slow/"), Arrays.asList("**/Broken*"));
    assertTrue(suite.takes("a/b/ThingIT.class"));
    assertTrue(suite.takes("a/slow/deep/Thing.class"));
    assertFalse(suite.takes("a/b/ThingTest.class"));
    assertFalse(suite.takes("a/b/BrokenIT.class"));
    assertFalse(Suite.gradle(Arrays.asList("**/*IT"), NONE).takes("a/b/ThingIT.class"));
    assertFalse(Suite.gradle(Arrays.asList("b/*IT.class"), NONE).takes("a/b/ThingIT.class"));
  }

  @Test
  void theClassesOfADirectoryAreNamedAsTheJvmNamesThem(@TempDir Path classes) throws Exception {
    for (String file : Arrays.asList("a/b/ThingTest.class", "a/b/Helper.class", "a/b/ThingTest$Inner.class", "TopTest.class", "a/notes.txt")) {
      Path path = classes.resolve(file);
      Files.createDirectories(path.getParent());
      Files.write(path, new byte[0]);
    }
    assertEquals(Arrays.asList("TopTest", "a.b.ThingTest"), surefire(NONE, NONE).classes(classes));
    assertEquals(
        Arrays.asList("TopTest", "a.b.Helper", "a.b.ThingTest", "a.b.ThingTest$Inner"),
        Suite.gradle(NONE, NONE).classes(classes));
    assertEquals(NONE, Suite.gradle(NONE, NONE).classes(classes.resolve("missing")));
  }
}
