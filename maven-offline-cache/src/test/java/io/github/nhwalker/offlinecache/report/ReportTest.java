package io.github.nhwalker.offlinecache.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportTest {

    @Test
    void exitCodes() {
        Report clean = new Report();
        clean.add(Issue.info("no-sources", "g:a:1", "x"));
        assertEquals(0, clean.exitCode(false, false));
        assertEquals(0, clean.exitCode(false, true));

        Report warn = new Report();
        warn.add(Issue.warning("hint-unmatched", "h", "x"));
        assertEquals(0, warn.exitCode(false, false));
        assertEquals(1, warn.exitCode(false, true));

        Report err = new Report();
        err.add(Issue.error("pom-not-found", "g:a:1", "x"));
        assertEquals(1, err.exitCode(false, false));
        assertEquals(0, err.exitCode(true, false));
        assertEquals(1, err.exitCode(true, true)); // allowed errors count as warnings
    }

    @Test
    void writesJsonAndSummary(@TempDir Path dir) throws Exception {
        Report report = new Report();
        report.add(Issue.error("pom-not-found", "g:a:1", "missing"));
        report.add(Issue.warning("hint-unmatched", "h", "unused"));
        report.add(Issue.info("no-sources", "g:b:1", "none"));

        Path json = dir.resolve("sub/report.json");
        report.writeJson(json);
        JsonNode root = new ObjectMapper().readTree(json.toFile());
        assertEquals(1, root.path("summary").path("errors").asInt());
        assertEquals(3, root.path("issues").size());
        assertEquals("ERROR", root.path("issues").get(0).path("severity").asText());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        report.printSummary(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("1 error(s), 1 warning(s), 1 info"));
        assertTrue(text.contains("pom-not-found"));
        assertFalse(text.contains("no-sources")); // infos only in report.json
    }
}
