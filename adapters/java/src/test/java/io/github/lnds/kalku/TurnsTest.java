package io.github.lnds.kalku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workers of a run build their copies one after another. Each is built against the one
 * local repository the user's Maven keeps, and Maven before 3.9 cannot fetch one artifact for
 * two builds at once: one of them fails.
 */
class TurnsTest {
  private static final Path SUMMONER = Paths.get("bin", "kalku-java").toAbsolutePath();
  private static final Path PROJECTS = Paths.get("src", "test", "projects");
  private static final int WORKERS = 3;

  @TempDir Path temp;

  // The build tool here writes down when it starts and when it ends, taking its time between,
  // and fails: whether two of them were ever running at once is in what it wrote.
  @Test
  void theWorkersOfOneReniBuildOneAtATime() throws Exception {
    Path project = temp.resolve("calc");
    CastTest.copy(PROJECTS.resolve("calc"), project);
    Path said = temp.resolve("builds.log");
    Path maven = temp.resolve("mvn");
    Files.write(
        maven,
        ("#!/bin/sh\necho start >> '" + said + "'\nsleep 1\necho end >> '" + said + "'\n"
                + "echo '[ERROR] built nothing'\nexit 1\n")
            .getBytes(StandardCharsets.UTF_8));
    Files.setPosixFilePermissions(maven, PosixFilePermissions.fromString("rwxr-xr-x"));

    List<Process> workers = new ArrayList<>();
    for (int worker = 0; worker < WORKERS; worker++) {
      workers.add(summon(project, maven, worker));
    }
    for (Process worker : workers) {
      String[] answers = answers(worker);
      assertTrue(answers[1].contains("prepare_failed"), answers[1]);
    }

    List<String> builds = Files.readAllLines(said, StandardCharsets.UTF_8);
    assertEquals(2 * WORKERS, builds.size(), builds.toString());
    for (int i = 0; i < builds.size(); i++) {
      assertEquals(i % 2 == 0 ? "start" : "end", builds.get(i), "two builds at once: " + builds);
    }
  }

  private Process summon(Path project, Path maven, int worker) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(SUMMONER.toString());
    builder.environment().put("KALKU_JAVA", Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    Process process = builder.start();
    String hello =
        "{\"type\":\"hello\",\"id\":1,\"protocol\":1,\"root\":"
            + Json.encode(project.toString())
            + ",\"reni\":"
            + Json.encode(temp.resolve("reni").toString())
            + ",\"worker\":"
            + worker
            + ",\"inline_limit_bytes\":65536,\"env\":{\"KALKU_MAVEN\":"
            + Json.encode(maven.toString())
            + "}}";
    OutputStream in = process.getOutputStream();
    in.write((hello + "\n{\"type\":\"prepare\",\"id\":2}\n").getBytes(StandardCharsets.UTF_8));
    in.flush();
    return process;
  }

  // The greeting and the answer to `prepare`, and then the kalku is told to go.
  private static String[] answers(Process process) throws Exception {
    BufferedReader out =
        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    String[] answers = {out.readLine(), out.readLine()};
    process.getOutputStream().close();
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the kalku did not exit");
    return answers;
  }
}
