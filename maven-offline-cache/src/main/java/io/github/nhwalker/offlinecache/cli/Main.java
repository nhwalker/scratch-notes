package io.github.nhwalker.offlinecache.cli;

import io.github.nhwalker.offlinecache.OfflineCacheRun;
import io.github.nhwalker.offlinecache.RunConfig;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Command line entry point (design doc §6, §8, §10).
 */
@Command(name = "maven-offline-cache",
        mixinStandardHelpOptions = true,
        version = "maven-offline-cache 0.1.0-SNAPSHOT",
        description = "Builds an offline Maven-layout repository from Gradle version catalogs.",
        footer = {"", "Environment:",
                "  NEXUS_USERNAME, NEXUS_PASSWORD   Nexus credentials (optional)",
                "  NEXUS_CENTRAL_URL                fallback for --central-url",
                "  NEXUS_PLUGINS_URL                fallback for --plugins-url"})
public final class Main implements Callable<Integer> {

    @Option(names = "--catalog", required = true, paramLabel = "FILE",
            description = "Gradle version catalog (repeatable).")
    List<Path> catalogs;

    @Option(names = "--out", required = true, paramLabel = "DIR",
            description = "Output directory (Maven layout).")
    Path outDir;

    @Option(names = "--report", paramLabel = "FILE", defaultValue = "report.json",
            description = "Report file (default: ${DEFAULT-VALUE}).")
    Path reportFile;

    @Option(names = "--central-url", paramLabel = "URL",
            description = "Nexus proxy URL for Maven Central (or NEXUS_CENTRAL_URL).")
    String centralUrl;

    @Option(names = "--plugins-url", paramLabel = "URL",
            description = "Nexus proxy URL for the Gradle Plugin Portal (or NEXUS_PLUGINS_URL).")
    String pluginsUrl;

    @Option(names = "--parallel", paramLabel = "N", defaultValue = "4",
            description = "Parallel downloads (default: ${DEFAULT-VALUE}).")
    int parallelism;

    @Option(names = "--retries", paramLabel = "N", defaultValue = "3",
            description = "Retries per download (default: ${DEFAULT-VALUE}).")
    int retries;

    @Option(names = "--allow-failures", description = "Errors do not fail the run.")
    boolean allowFailures;

    @Option(names = "--fail-on-warning", description = "Warnings fail the run.")
    boolean failOnWarning;

    @Option(names = "--dry-run", description = "Only read the catalogs and print seeds and hints.")
    boolean dryRun;

    private final Map<String, String> env;

    public Main() {
        this(System.getenv());
    }

    Main(Map<String, String> env) {
        this.env = env;
    }

    @Override
    public Integer call() {
        RunConfig config = toConfig();
        return new OfflineCacheRun(config, System.out).run();
    }

    RunConfig toConfig() {
        return new RunConfig(catalogs, outDir, reportFile,
                firstNonBlank(centralUrl, env.get("NEXUS_CENTRAL_URL")),
                firstNonBlank(pluginsUrl, env.get("NEXUS_PLUGINS_URL")),
                blankToNull(env.get("NEXUS_USERNAME")),
                blankToNull(env.get("NEXUS_PASSWORD")),
                parallelism, retries, allowFailures, failOnWarning, dryRun);
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : blankToNull(b);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
