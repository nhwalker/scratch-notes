package io.github.nhwalker.offlinecache.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects {@link Issue}s for a run and decides the exit code (design doc §8).
 * Thread-safe: issues may be added from parallel download workers.
 */
public final class Report {

    private final List<Issue> issues = new ArrayList<>();

    public synchronized void add(Issue issue) {
        issues.add(issue);
    }

    public synchronized void addAll(Collection<Issue> more) {
        issues.addAll(more);
    }

    public synchronized List<Issue> issues() {
        return List.copyOf(issues);
    }

    public synchronized long count(Severity severity) {
        return issues.stream().filter(i -> i.severity() == severity).count();
    }

    /**
     * @param allowFailures  {@code --allow-failures}: errors do not fail the run
     * @param failOnWarning  {@code --fail-on-warning}: warnings fail the run
     * @return 0 on success, 1 on failure
     */
    public int exitCode(boolean allowFailures, boolean failOnWarning) {
        boolean failed = (count(Severity.ERROR) > 0 && !allowFailures)
                || (failOnWarning && (count(Severity.WARNING) > 0 || (allowFailures && count(Severity.ERROR) > 0)));
        return failed ? 1 : 0;
    }

    /** Console summary: totals, then every error and warning (infos only go to report.json). */
    public void printSummary(PrintStream out) {
        List<Issue> snapshot = issues();
        out.printf("maven-offline-cache: %d error(s), %d warning(s), %d info%n",
                count(Severity.ERROR), count(Severity.WARNING), count(Severity.INFO));
        snapshot.stream().filter(i -> i.severity() == Severity.ERROR).forEach(i -> out.println("  " + i));
        snapshot.stream().filter(i -> i.severity() == Severity.WARNING).forEach(i -> out.println("  " + i));
    }

    public void writeJson(Path file) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("errors", count(Severity.ERROR));
        summary.put("warnings", count(Severity.WARNING));
        summary.put("infos", count(Severity.INFO));
        root.put("summary", summary);
        root.put("issues", issues());

        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(file.toFile(), root);
    }
}
