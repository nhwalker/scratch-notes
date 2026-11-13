package io.github.nhwalker.offlinecache.cli;

import io.github.nhwalker.offlinecache.RunConfig;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @Test
    void envFallbackAndDefaults() {
        Main main = new Main(Map.of(
                "NEXUS_CENTRAL_URL", "https://env/central/",
                "NEXUS_USERNAME", "u",
                "NEXUS_PASSWORD", "p"));
        new CommandLine(main).parseArgs("--catalog", "a.toml", "--catalog", "b.toml", "--out", "repo");

        RunConfig c = main.toConfig();
        assertEquals(List.of(Path.of("a.toml"), Path.of("b.toml")), c.catalogs());
        assertEquals("https://env/central/", c.centralUrl());
        assertNull(c.pluginsUrl());
        assertEquals("u", c.username());
        assertEquals(4, c.parallelism());
        assertEquals(3, c.retries());
        assertEquals(Path.of("report.json"), c.reportFile());
        assertFalse(c.toString().contains("=p,")); // password masked
        assertTrue(c.toString().contains("****"));
    }

    @Test
    void optionOverridesEnv() {
        Main main = new Main(Map.of("NEXUS_CENTRAL_URL", "https://env/central/"));
        new CommandLine(main).parseArgs("--catalog", "a.toml", "--out", "repo",
                "--central-url", "https://cli/central/", "--plugins-url", "https://cli/plugins/");
        RunConfig c = main.toConfig();
        assertEquals("https://cli/central/", c.centralUrl());
        assertEquals("https://cli/plugins/", c.pluginsUrl());
        assertNull(c.username());
    }
}
