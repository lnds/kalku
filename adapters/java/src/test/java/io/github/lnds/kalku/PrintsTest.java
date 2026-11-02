package io.github.lnds.kalku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The fingerprints of what tests ran, as one instance takes them over and over. */
class PrintsTest {
  // A project of two modules, `app` using `core`, each with `classes` class files and a data file.
  private static Maven.Reactor project(Path root, int classes) throws Exception {
    Files.write(root.resolve("pom.xml"), "<project/>".getBytes(StandardCharsets.UTF_8));
    Maven.Reactor reactor = new Maven.Reactor();
    reactor.root = root;
    for (String name : List.of("core", "app")) {
      Path dir = Files.createDirectories(root.resolve(name));
      Files.write(dir.resolve("pom.xml"), "<project/>".getBytes(StandardCharsets.UTF_8));
      Path resources = Files.createDirectories(dir.resolve("src/test/resources"));
      Files.write(resources.resolve("data.txt"), name.getBytes(StandardCharsets.UTF_8));
      Maven.Build module = new Maven.Build();
      module.project = root;
      module.dir = dir;
      module.name = name;
      module.classes = Files.createDirectories(dir.resolve("target/classes"));
      module.testClasses = Files.createDirectories(dir.resolve("target/test-classes"));
      module.sources.add(dir.resolve("src/main/java"));
      module.testSources.add(dir.resolve("src/test/java"));
      for (int i = 0; i < classes; i++) {
        Files.write(module.classes.resolve("C" + i + ".class"), new byte[] {(byte) i, 1, 2, 3});
      }
      reactor.modules.add(module);
    }
    reactor.modules.get(1).uses.add(reactor.modules.get(0));
    return reactor;
  }

  private static Prints prints(Maven.Reactor project, Path kept) {
    return new Prints(project, kept, "setting", new HashMap<>());
  }

  private static List<String> classesOf(Maven.Build module, int count) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      out.add(module.dir.getFileName() + "/target/classes/C" + i + ".class");
    }
    return out;
  }

  @Test
  void oneInstanceGivesWhatAFreshOneGivesForEveryClass(@TempDir Path root, @TempDir Path kept)
      throws Exception {
    Maven.Reactor project = project(root, 20);
    Prints kept1 = prints(project, kept);
    for (Maven.Build module : project.modules) {
      for (int owner = 0; owner < 5; owner++) {
        List<String> classes = classesOf(module, 10 + owner);
        assertEquals(
            prints(project, kept).of(module, "T" + owner, classes),
            kept1.of(module, "T" + owner, classes));
      }
    }
  }

  @Test
  void whatChangedIsReadAgainOnceForgotten(@TempDir Path root, @TempDir Path kept)
      throws Exception {
    Maven.Reactor project = project(root, 3);
    Maven.Build app = project.modules.get(1);
    List<String> classes = classesOf(app, 3);
    Prints prints = prints(project, kept);
    String before = prints.of(app, "T", classes);
    Files.write(app.classes.resolve("C0.class"), new byte[] {9, 9});
    assertEquals(before, prints.of(app, "T", classes));
    prints.forget();
    String after = prints.of(app, "T", classes);
    assertNotEquals(before, after);
    assertEquals(prints(project, kept).of(app, "T", classes), after);
  }

  // A class every test runs goes into the fingerprint of every test class: read once, not once
  // per test class.
  @Test
  void manyTestClassesOverManySharedClassesStayFast(@TempDir Path root, @TempDir Path kept)
      throws Exception {
    Maven.Reactor project = project(root, 1000);
    Maven.Build app = project.modules.get(1);
    List<String> classes = classesOf(app, 1000);
    Prints prints = prints(project, kept);
    assertTimeoutPreemptively(
        Duration.ofSeconds(20),
        () -> {
          for (int owner = 0; owner < 2000; owner++) {
            prints.of(app, "T" + owner, classes);
          }
        });
  }
}
