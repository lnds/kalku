package io.github.lnds.kalku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A kalku whose run is gone. A run that is killed asks nothing of its kalku: the input just
 * ends, with no `shutdown` on it, in the middle of a request that has minutes left to run.
 * The kalku ends then, and so does everything it started.
 */
class OrphanedTest {
  private static final Path SUMMONER = Paths.get("bin", "kalku-java").toAbsolutePath();
  private static final Path PROJECTS = Paths.get("src", "test", "projects");
  private static final String STALLS = "fx.StallTest#stalls()";

  @TempDir Path temp;

  // A number nothing else on this machine sleeps for, so the program can be told apart.
  private final String mark = String.valueOf(40_000 + (System.nanoTime() % 20_000));
  private Path project;
  // While this file is there, the test that was added to the project starts a program and
  // takes longer than anybody waits.
  private Path stall;
  private Process kalku;
  private OutputStream in;
  private BufferedReader out;
  private final List<ProcessHandle> started = new ArrayList<>();

  @BeforeEach
  void aProjectWithATestThatCanStall() throws IOException {
    project = temp.resolve("calc");
    stall = temp.resolve("stall");
    CastTest.copy(PROJECTS.resolve("calc"), project);
    Files.write(
        project.resolve("src/test/java/fx/StallTest.java"),
        String.join(
                "\n",
                "package fx;",
                "",
                "import java.nio.file.Files;",
                "import java.nio.file.Paths;",
                "import org.junit.jupiter.api.Test;",
                "",
                "class StallTest {",
                "  @Test",
                "  void stalls() throws Exception {",
                "    if (Files.exists(Paths.get(System.getenv(\"KALKU_STALL\")))) {",
                "      new ProcessBuilder(\"sleep\", \"" + mark + "\").start();",
                "      Thread.sleep(600_000);",
                "    }",
                "  }",
                "}",
                "")
            .getBytes(StandardCharsets.UTF_8));
  }

  // Whatever a kalku that did not end left behind is ended here, by what it was seen to start.
  @AfterEach
  void nothingIsLeftRunning() {
    for (ProcessHandle each : started) {
      each.destroyForcibly();
    }
    if (kalku != null) {
      kalku.descendants().forEach(ProcessHandle::destroyForcibly);
      kalku.destroyForcibly();
    }
  }

  private void summon() throws IOException {
    ProcessBuilder builder = new ProcessBuilder(SUMMONER.toString());
    builder.environment().put("KALKU_JAVA", Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    kalku = builder.start();
    in = kalku.getOutputStream();
    out = new BufferedReader(new InputStreamReader(kalku.getInputStream(), StandardCharsets.UTF_8));
  }

  private String hello() {
    Map<String, Object> env = new LinkedHashMap<>();
    env.put("KALKU_STALL", stall.toString());
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("type", "hello");
    o.put("id", 1L);
    o.put("protocol", 1L);
    o.put("root", project.toString());
    o.put("reni", temp.resolve("reni").toString());
    o.put("worker", 0L);
    o.put("inline_limit_bytes", 65536L);
    o.put("env", env);
    return Json.encode(o);
  }

  private void tell(String line) throws IOException {
    in.write((line + "\n").getBytes(StandardCharsets.UTF_8));
    in.flush();
  }

  private Map<?, ?> heard() throws Exception {
    String line = out.readLine();
    assertTrue(line != null, "the kalku closed its output");
    return (Map<?, ?>) Json.decode(line);
  }

  // The input ends once the request has started its program, not before; and then the kalku
  // and everything that was running under it are gone within a couple of seconds.
  private void endsWithWhatItStarted() throws Exception {
    long deadline = System.nanoTime() + 120_000_000_000L;
    while (kalku.descendants().noneMatch(p -> p.info().commandLine().orElse("").contains("sleep " + mark))) {
      assertTrue(System.nanoTime() < deadline, "the request never got to the slow part");
      assertTrue(kalku.isAlive(), "the kalku left before the request got to the slow part");
      Thread.sleep(50);
    }
    started.addAll(kalku.descendants().collect(Collectors.toList()));

    in.close();

    assertTrue(kalku.waitFor(3, TimeUnit.SECONDS), "the kalku outlived its run");
    deadline = System.nanoTime() + 2_000_000_000L;
    while (started.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    List<String> left =
        started.stream()
            .filter(ProcessHandle::isAlive)
            .map(p -> p.pid() + " " + p.info().commandLine().orElse("?"))
            .collect(Collectors.toList());
    assertTrue(left.isEmpty(), "what the kalku started outlived it: " + left);
    assertEquals(null, out.readLine(), "the request was answered for nobody");
  }

  // Maven is a program the kalku starts, and what stalls is a program Maven starts.
  @Test
  void aMavenBuildUnderWayEndsWithTheRun() throws Exception {
    Path pom = project.resolve("pom.xml");
    String stalling =
        String.join(
            "\n",
            "      <plugin>",
            "        <groupId>org.apache.maven.plugins</groupId>",
            "        <artifactId>maven-antrun-plugin</artifactId>",
            "        <version>3.1.0</version>",
            "        <executions>",
            "          <execution>",
            "            <phase>generate-sources</phase>",
            "            <goals><goal>run</goal></goals>",
            "            <configuration>",
            "              <target><exec executable=\"sleep\"><arg value=\"" + mark + "\"/></exec></target>",
            "            </configuration>",
            "          </execution>",
            "        </executions>",
            "      </plugin>",
            "    </plugins>");
    String text = new String(Files.readAllBytes(pom), StandardCharsets.UTF_8);
    assertTrue(text.contains("    </plugins>"));
    Files.write(pom, text.replace("    </plugins>", stalling).getBytes(StandardCharsets.UTF_8));
    summon();
    tell(hello());
    assertEquals("ready", heard().get("type"));

    tell("{\"type\":\"prepare\",\"id\":2}");

    endsWithWhatItStarted();
  }

  // Gradle too, and the JVM it builds in is one more program between the kalku and the stall.
  @Test
  void aGradleBuildUnderWayEndsWithTheRun() throws Exception {
    Assumptions.assumeTrue(CastTest.gradleRuns(temp), "no Gradle that runs on this JDK");
    Files.delete(project.resolve("pom.xml"));
    Path build = PROJECTS.resolve("gradle").resolve("calc");
    Files.copy(build.resolve("settings.gradle"), project.resolve("settings.gradle"));
    Files.write(
        project.resolve("build.gradle"),
        (new String(Files.readAllBytes(build.resolve("build.gradle")), StandardCharsets.UTF_8)
                + "\ntasks.named('compileJava') {\n  doFirst { ['sleep', '" + mark + "'].execute().waitFor() }\n}\n")
            .getBytes(StandardCharsets.UTF_8));
    summon();
    tell(hello());
    assertEquals("ready", heard().get("type"));

    tell("{\"type\":\"prepare\",\"id\":2}");

    endsWithWhatItStarted();
  }

  @Test
  void aBaselineUnderWayEndsWithTheRun() throws Exception {
    summon();
    tell(hello());
    assertEquals("ready", heard().get("type"));
    tell("{\"type\":\"prepare\",\"id\":2}");
    Map<?, ?> prepared = heard();
    assertEquals("prepared", prepared.get("type"), prepared.toString());
    Files.write(stall, new byte[0]);

    tell("{\"type\":\"baseline\",\"id\":3}");

    endsWithWhatItStarted();
  }

  @Test
  void aCastUnderWayEndsWithTheRun() throws Exception {
    summon();
    tell(hello());
    assertEquals("ready", heard().get("type"));
    tell("{\"type\":\"prepare\",\"id\":2}");
    Map<?, ?> prepared = heard();
    assertEquals("prepared", prepared.get("type"), prepared.toString());
    tell(CastTest.sitesOf("src/main/java/fx/Calc.java"));
    Map<?, ?> site = (Map<?, ?>) ((List<?>) heard().get("sites")).get(0);
    Files.write(stall, new byte[0]);

    tell(CastTest.castOf(site, Collections.singletonList(STALLS)));

    endsWithWhatItStarted();
  }

  // Input that ends after `shutdown` is a driver that wrote everything it had to say and
  // closed: the end of its input is there to be read while the first request is under way,
  // and every request is still answered.
  @Test
  void inputThatEndsAfterAShutdownHasEveryRequestAnswered() throws Exception {
    summon();
    tell(hello());
    assertEquals("ready", heard().get("type"));
    tell(CastTest.sitesOf("src/main/java/fx/Calc.java"));
    Map<?, ?> noticed = null;
    for (Object each : (List<?>) heard().get("sites")) {
      if (">=".equals(((Map<?, ?>) each).get("original"))) {
        noticed = (Map<?, ?>) each;
      }
    }

    tell("{\"type\":\"prepare\",\"id\":2}");
    tell("{\"type\":\"baseline\",\"id\":4}");
    tell(CastTest.castOf(noticed, Collections.singletonList("fx.CalcTest#tenIsBigAndNineIsNot()")));
    tell("{\"type\":\"shutdown\",\"id\":6}");
    in.close();

    List<Map<?, ?>> said = new ArrayList<>();
    String line;
    while ((line = out.readLine()) != null) {
      said.add((Map<?, ?>) Json.decode(line));
    }
    assertEquals(4, said.size(), said.toString());
    assertEquals("prepared", said.get(0).get("type"), said.toString());
    assertEquals("green", said.get(1).get("status"), said.toString());
    assertEquals("killed", said.get(2).get("outcome"), said.toString());
    assertEquals("bye", said.get(3).get("type"));
    assertTrue(kalku.waitFor(10, TimeUnit.SECONDS), "the kalku did not exit");
    assertFalse(kalku.descendants().findAny().isPresent());
  }
}
