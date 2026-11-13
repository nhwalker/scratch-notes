package io.github.nhwalker.offlinecache;

import io.github.nhwalker.offlinecache.report.Issue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineCacheRunTest {

    @Test
    void dryRunPrintsPlan(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("libs.versions.toml");
        Files.writeString(catalog, """
                [libraries]
                guava = "com.google.guava:guava:33.0-jre"
                """);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = new OfflineCacheRun(config(dir, catalog, null, true),
                new PrintStream(bytes, true, StandardCharsets.UTF_8)).run();

        assertEquals(0, exit);
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("com.google.guava:guava:33.0-jre"));
        assertTrue(Files.exists(dir.resolve("report.json")));
    }

    @Test
    void missingCentralUrlFails(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("libs.versions.toml");
        Files.writeString(catalog, "[libraries]\n");
        int exit = new OfflineCacheRun(config(dir, catalog, null, false),
                (c, r) -> Set.of(), new PrintStream(new ByteArrayOutputStream())).run();
        assertEquals(1, exit);
    }

    @Test
    void walkerErrorsFailRun(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("libs.versions.toml");
        Files.writeString(catalog, "[libraries]\n");
        int exit = new OfflineCacheRun(config(dir, catalog, "https://nexus/central/", false),
                (c, r) -> {
                    r.add(Issue.error("pom-not-found", "g:a:1", "x"));
                    return Set.of();
                },
                new PrintStream(new ByteArrayOutputStream())).run();
        assertEquals(1, exit);
    }

    @Test
    void missingCatalogIsError(@TempDir Path dir) {
        int exit = new OfflineCacheRun(config(dir, dir.resolve("nope.toml"), null, true),
                new PrintStream(new ByteArrayOutputStream())).run();
        assertEquals(1, exit);
    }

    private static RunConfig config(Path dir, Path catalog, String centralUrl, boolean dryRun) {
        return new RunConfig(List.of(catalog), dir.resolve("repo"), dir.resolve("report.json"),
                centralUrl, null, null, null, 4, 3, false, false, dryRun);
    }
}
