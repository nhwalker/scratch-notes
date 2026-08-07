package io.github.nhwalker.scratchnotes.junit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(LoggingAttachmentExtension.class)
class LoggingAttachmentExtensionDemoTest {

  @Test
  void simpleTest() {
    System.out.println("OUT-MARKER-simple");
    System.err.println("ERR-MARKER-simple");
  }

  @ParameterizedTest
  @ValueSource(strings = {"alpha", "beta"})
  void paramTest(String value) {
    System.out.println("OUT-MARKER-" + value);
    System.err.println("ERR-MARKER-" + value);
  }

  @Test
  void interleaveTest() {
    System.out.print("no-newline-out");
    System.err.println("err-after");
    System.out.println();
  }

  @AfterAll
  static void verifyLogs() throws IOException {
    Path dir = Path.of("target", "test-logs", "LoggingAttachmentExtensionDemoTest");

    // Both stdout and stderr must land in the one file for the test, each
    // line prefixed with its source stream. Under parallel execution a file
    // may also contain other tests' markers, but it always contains its own.
    Path simpleLog = dir.resolve("simpleTest_0.log");
    assertTrue(Files.exists(simpleLog), "missing " + simpleLog);
    String simpleContent = Files.readString(simpleLog);
    assertTrue(simpleContent.contains("[STD] OUT-MARKER-simple"), "prefixed stdout marker not captured");
    assertTrue(simpleContent.contains("[ERR] ERR-MARKER-simple"), "prefixed stderr marker not captured");

    // Each parameterized invocation gets its own indexed file.
    assertTrue(Files.exists(dir.resolve("paramTest_0.log")), "missing paramTest_0.log");
    assertTrue(Files.exists(dir.resolve("paramTest_1.log")), "missing paramTest_1.log");

    // A stream interleaving into another stream's unterminated line must break
    // the line and start its own tagged line: stdout left "no-newline-out"
    // open, so "err-after" must appear on an [ERR]-tagged line of its own,
    // never appended to the open [STD] line.
    Path interleaveLog = dir.resolve("interleaveTest_0.log");
    assertTrue(Files.exists(interleaveLog), "missing " + interleaveLog);
    boolean sawOut = false;
    boolean sawErr = false;
    for (String line : Files.readAllLines(interleaveLog)) {
      if (line.contains("no-newline-out")) {
        sawOut = true;
        assertTrue(line.startsWith("[STD] "), "stdout text on non-[STD] line: " + line);
        assertTrue(!line.contains("err-after"), "streams merged on one line: " + line);
      }
      if (line.contains("err-after")) {
        sawErr = true;
        assertTrue(line.startsWith("[ERR] "), "stderr text on non-[ERR] line: " + line);
      }
    }
    assertTrue(sawOut, "stdout text missing from interleave log");
    assertTrue(sawErr, "stderr text missing from interleave log");

    // No line in any file may escape without a source prefix.
    try (var files = Files.list(dir)) {
      for (Path log : files.toList()) {
        for (String line : Files.readAllLines(log)) {
          if (!line.isBlank()) {
            assertTrue(line.startsWith("[STD] ") || line.startsWith("[ERR] "),
                "unprefixed line in " + log.getFileName() + ": " + line);
          }
        }
      }
    }
  }
}
