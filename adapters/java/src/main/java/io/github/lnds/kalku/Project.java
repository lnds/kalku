package io.github.lnds.kalku;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The project's copy in the reni: where it is built and where its tests run.
 *
 * <p>A build writes beside the sources it builds, and the user's tree is never written to. So
 * the tree is copied, and everything a build or a test leaves behind is left in the copy.
 */
final class Project {
  private Project() {}

  // At the top of the project: what a build leaves, what version control keeps, and what is
  // kalku's own. Deeper down the same names are ordinary packages. kalku's files are left out
  // because they are not the project's: a build that checks every file for a licence header
  // would fail on them.
  private static final List<String> NOT_COPIED =
      Arrays.asList("target", ".git", ".kalku", ".kalku.toml");

  /**
   * Makes {@code copy} hold what {@code root} holds, and says how many files changed.
   *
   * <p>Files are compared by content. One that is the same is left alone, so its time stays
   * what it was; one that differs is written now. A build decides what is stale by time: a copy
   * that kept the times of its sources, next to what an earlier version of them was built into,
   * would be trusted, and a stale class measured.
   */
  static int sync(Path root, Path copy, Path reni) throws IOException {
    Path top = root.toAbsolutePath().normalize();
    Path inside = reni.toAbsolutePath().normalize();
    Set<Path> kept = new HashSet<>();
    int[] changed = {0};
    Files.createDirectories(copy);
    Files.walkFileTree(
        top,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            if (dir.equals(inside)
                || (top.equals(dir.getParent()) && skipped(dir))
                || output(dir)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            Path to = copy.resolve(top.relativize(dir).toString());
            kept.add(to);
            Files.createDirectories(to);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            // Only what is a file is copied: a link is not followed out of the project.
            if (attrs.isRegularFile() && !(top.equals(file.getParent()) && skipped(file))) {
              Path to = copy.resolve(top.relativize(file).toString());
              kept.add(to);
              if (!same(file, to)) {
                Files.copy(
                    file,
                    to,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
                Files.setLastModifiedTime(
                    to, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                changed[0]++;
              }
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return changed[0] + forget(copy, kept);
  }

  private static boolean skipped(Path dir) {
    return NOT_COPIED.contains(dir.getFileName().toString());
  }

  private static boolean same(Path a, Path b) throws IOException {
    if (!Files.isRegularFile(b, LinkOption.NOFOLLOW_LINKS) || Files.size(a) != Files.size(b)) {
      return false;
    }
    return Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b));
  }

  // What the project no longer has is taken out of the copy; what a build left stays.
  private static int forget(Path copy, Set<Path> kept) throws IOException {
    List<Path> gone;
    try (Stream<Path> all = Files.walk(copy)) {
      gone =
          all.filter(p -> !p.equals(copy) && !kept.contains(p) && !built(copy, p))
              .sorted(Comparator.reverseOrder())
              .collect(Collectors.toList());
    }
    for (Path p : gone) {
      Files.deleteIfExists(p);
    }
    return gone.size();
  }

  private static boolean built(Path copy, Path p) {
    if (NOT_COPIED.contains(copy.relativize(p).getName(0).toString())) {
      return true;
    }
    for (Path at = p; at != null && !at.equals(copy); at = at.getParent()) {
      if (output(at)) {
        return true;
      }
    }
    return false;
  }

  // What a build wrote: the `target` beside a `pom.xml`, or Gradle's directories beside a
  // Gradle build, in the project and in each of its modules. What the user's own build left there is not copied, since a build goes by times
  // and would trust classes that are older than the sources beside them; and what the build
  // in the reni left there is not taken away.
  private static boolean output(Path dir) {
    if (dir.getFileName() == null || dir.getParent() == null) {
      return false;
    }
    String name = dir.getFileName().toString();
    if (name.equals("target")) {
      return Files.isRegularFile(dir.getParent().resolve("pom.xml"));
    }
    // Gradle's are `build` and `.gradle`, beside a build or a settings file.
    return (name.equals("build") || name.equals(".gradle")) && Gradle.builds(dir.getParent());
  }

  /** Every {@code .java} file under these directories, in a stable order. */
  static List<Path> sources(List<Path> roots) throws IOException {
    List<Path> out = new java.util.ArrayList<>();
    for (Path root : roots) {
      if (Files.isDirectory(root)) {
        try (Stream<Path> all = Files.walk(root)) {
          all.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
              .sorted()
              .forEach(out::add);
        }
      }
    }
    return out;
  }

  /** Empties a directory and leaves it there. */
  static void clear(Path dir) throws IOException {
    if (Files.exists(dir)) {
      List<Path> all;
      try (Stream<Path> walk = Files.walk(dir)) {
        all = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
      }
      for (Path p : all) {
        Files.deleteIfExists(p);
      }
    }
    Files.createDirectories(dir);
  }
}
