package io.github.lnds.kalku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A real kalku over a real pipe, summoned the way the kaikai side summons it. Nothing smaller
 * shows that the channel carries the protocol and nothing else.
 */
class PipeTest {
  private static final Path SUMMONER = Paths.get("bin", "kalku-java").toAbsolutePath();
  private static final String THIS_JAVA =
      Paths.get(System.getProperty("java.home"), "bin", "java").toString();

  @TempDir Path root;

  private static final class Ran {
    int exit;
    List<String> out = new ArrayList<>();
    String err;
  }

  private Ran summon(String java, String... requests) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(SUMMONER.toString());
    builder.environment().put("KALKU_JAVA", java);
    // The platform's own charset must not be what the channel is read and written in.
    builder.environment().put("LC_ALL", "C");
    builder.environment().remove("LANG");
    Process process = builder.start();
    // A summoner that turns the runtime away exits without reading: writing to it then fails,
    // or does not, as the scheduler has it. What it said and how it exited is what is judged.
    try (OutputStream in = process.getOutputStream()) {
      in.write((String.join("\n", requests) + "\n").getBytes(StandardCharsets.UTF_8));
    } catch (IOException gone) {
      // Nothing was listening.
    }
    Ran ran = new Ran();
    Thread errors =
        new Thread(
            () -> {
              try {
                ran.err = new String(drain(process.getErrorStream()), StandardCharsets.UTF_8);
              } catch (Exception e) {
                ran.err = e.toString();
              }
            });
    errors.start();
    String said = new String(drain(process.getInputStream()), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the kalku did not exit");
    errors.join();
    ran.exit = process.exitValue();
    if (!said.isEmpty()) {
      ran.out.addAll(Arrays.asList(said.split("\n")));
    }
    return ran;
  }

  private static byte[] drain(InputStream in) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = in.read(buffer)) >= 0) {
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }

  private String hello() {
    return "{\"type\":\"hello\",\"id\":1,\"protocol\":1,\"root\":"
        + Json.encode(root.toString())
        + ",\"reni\":"
        + Json.encode(root.resolve(".reni").toString())
        + ",\"worker\":0,\"inline_limit_bytes\":65536,\"env\":{}}";
  }

  @Test
  void everyLineOnStdoutIsTheProtocolWhateverTheLocale() throws Exception {
    Path source = root.resolve("src/main/java/fx/unicode/NonAscii.java");
    Files.createDirectories(source.getParent());
    Files.copy(GoldenTest.FIXTURES.resolve("unicode").resolve("NonAscii.java"), source);

    Ran ran =
        summon(
            THIS_JAVA,
            hello(),
            "{\"type\":\"sites\",\"id\":2,\"files\":[\"src/main/java/fx/unicode/NonAscii.java\"],"
                + "\"spells\":[\"literal\",\"compare\"],\"exclude_calls\":[]}",
            "{\"type\":\"prepare\",\"id\":3}",
            "this line is not the protocol",
            "{\"type\":\"shutdown\",\"id\":4}");

    assertEquals(0, ran.exit, ran.err);
    assertEquals(5, ran.out.size(), ran.out + "\n" + ran.err);
    List<Map<?, ?>> said = new ArrayList<>();
    for (String line : ran.out) {
      said.add((Map<?, ?>) Json.decode(line));
    }
    assertEquals("ready", said.get(0).get("type"));
    assertEquals("sites_found", said.get(1).get("type"));
    assertEquals("not_implemented", said.get(2).get("code"));
    assertEquals("bad_request", said.get(3).get("code"));
    assertEquals("bye", said.get(4).get("type"));

    // What is not ASCII crossed the pipe as it is in the file.
    List<String> originals = new ArrayList<>();
    for (Object site : (List<?>) said.get(1).get("sites")) {
      originals.add((String) ((Map<?, ?>) site).get("original"));
    }
    assertTrue(originals.contains("\"ñandú\""), originals.toString());
    assertTrue(originals.contains("\"😀😀\""), originals.toString());
  }

  @Test
  void endOfInputIsAnExitNotAHang() throws Exception {
    Ran ran = summon(THIS_JAVA, hello());
    assertEquals(0, ran.exit, ran.err);
    assertEquals(1, ran.out.size());
  }

  // A Java older than the floor cannot load the kalku at all, so it is the summoner that
  // says so, by name, and the channel stays empty.
  @Test
  void aJavaTooOldIsTurnedAwayByNameBeforeAnythingIsSaid() throws Exception {
    Path old = root.resolve("java8");
    Files.write(
        old,
        "#!/bin/sh\necho 'java version \"1.8.0_292\"' >&2\necho 'Java(TM) SE Runtime Environment' >&2\n"
            .getBytes(StandardCharsets.UTF_8));
    Files.setPosixFilePermissions(old, PosixFilePermissions.fromString("rwxr-xr-x"));

    Ran ran = summon(old.toString(), hello());

    assertEquals(1, ran.exit);
    assertTrue(ran.out.isEmpty(), ran.out.toString());
    assertTrue(ran.err.contains("needs Java 11 or newer"), ran.err);
    assertTrue(ran.err.contains("1.8.0_292"), ran.err);
  }

  // A runtime cut down to less than a JDK has no compiler to read sources with. That is an
  // answer in the protocol, with the reason, not a crash on the way up.
  @Test
  void aRuntimeWithoutACompilerSaysSoInTheProtocol() throws Exception {
    Path jre = root.resolve("jre");
    Process jlink =
        new ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", "jlink").toString(),
                "--add-modules",
                "java.base",
                "--output",
                jre.toString())
            .redirectErrorStream(true)
            .start();
    String said = new String(drain(jlink.getInputStream()), StandardCharsets.UTF_8);
    assertEquals(0, jlink.waitFor(), said);

    Ran ran = summon(jre.resolve("bin").resolve("java").toString(), hello());

    assertEquals(0, ran.exit, ran.err);
    assertEquals(1, ran.out.size(), ran.out + "\n" + ran.err);
    Map<?, ?> reply = (Map<?, ?>) Json.decode(ran.out.get(0));
    assertEquals("toolchain_missing", reply.get("code"));
    assertEquals(true, reply.get("fatal"));
    assertFalse(((String) reply.get("message")).isEmpty());
  }
}
