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

  @AfterAll
  static void verifyLogs() throws IOException {
    Path dir = Path.of("target", "test-logs", "LoggingAttachmentExtensionDemoTest");

    // Both stdout and stderr must land in the one file for the test.
    // Under parallel execution a file may also contain other tests' markers,
    // but it always contains its own.
    Path simpleLog = dir.resolve("simpleTest_0.log");
    assertTrue(Files.exists(simpleLog), "missing " + simpleLog);
    String simpleContent = Files.readString(simpleLog);
    assertTrue(simpleContent.contains("OUT-MARKER-simple"), "stdout marker not captured");
    assertTrue(simpleContent.contains("ERR-MARKER-simple"), "stderr marker not captured");

    // Each parameterized invocation gets its own indexed file.
    assertTrue(Files.exists(dir.resolve("paramTest_0.log")), "missing paramTest_0.log");
    assertTrue(Files.exists(dir.resolve("paramTest_1.log")), "missing paramTest_1.log");
  }
}
