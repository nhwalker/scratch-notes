package io.github.nhwalker.offlinecache;

import io.github.nhwalker.offlinecache.catalog.Catalog;
import io.github.nhwalker.offlinecache.catalog.CatalogEntry;
import io.github.nhwalker.offlinecache.catalog.CatalogReader;
import io.github.nhwalker.offlinecache.catalog.ClassifierHint;
import io.github.nhwalker.offlinecache.mirror.Checksums;
import io.github.nhwalker.offlinecache.mirror.OutputCleaner;
import io.github.nhwalker.offlinecache.report.Issue;
import io.github.nhwalker.offlinecache.report.Report;
import io.github.nhwalker.offlinecache.walk.ResolverWalker;
import io.github.nhwalker.offlinecache.walk.Walker;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates a run: read catalogs → walk/download → cleanup → report (design doc §3).
 */
public final class OfflineCacheRun {

    private final RunConfig config;
    private final Walker walker;
    private final PrintStream out;

    public OfflineCacheRun(RunConfig config, PrintStream out) {
        this(config, new ResolverWalker(config), out);
    }

    OfflineCacheRun(RunConfig config, Walker walker, PrintStream out) {
        this.config = config;
        this.walker = walker;
        this.out = out;
    }

    /** @return process exit code */
    public int run() {
        Report report = new Report();

        List<Catalog> catalogs = new ArrayList<>();
        for (Path file : config.catalogs()) {
            if (!Files.isRegularFile(file)) {
                report.add(Issue.error("catalog-not-found", file.toString(), "catalog file does not exist"));
                continue;
            }
            try {
                catalogs.add(CatalogReader.read(file));
            } catch (IOException e) {
                report.add(Issue.error("catalog-read-error", file.toString(), e.toString()));
            }
        }
        Catalog catalog = Catalog.merge(catalogs);
        report.addAll(catalog.issues());

        if (config.dryRun()) {
            printPlan(catalog);
        } else if (config.centralUrl() == null || config.centralUrl().isBlank()) {
            report.add(Issue.error("config-missing", "central-url",
                    "no Maven Central proxy URL: use --central-url or NEXUS_CENTRAL_URL"));
        } else {
            Set<Gav> visited = walker.walk(catalog, report);
            out.println("Visited " + visited.size() + " coordinate(s).");
            try {
                OutputCleaner.clean(config.outDir());
            } catch (IOException e) {
                report.add(Issue.error("cleanup-failed", config.outDir().toString(), e.toString()));
            }
            try {
                int generated = Checksums.writeMissingForTree(config.outDir());
                if (generated > 0) {
                    report.add(Issue.info("checksums-generated", config.outDir().toString(),
                            "generated missing md5/sha1/sha256/sha512 sidecars for " + generated + " file(s)"));
                }
            } catch (IOException e) {
                report.add(Issue.error("checksum-generation-failed", config.outDir().toString(), e.toString()));
            }
        }

        try {
            report.writeJson(config.reportFile());
        } catch (IOException e) {
            report.add(Issue.error("report-write-failed", config.reportFile().toString(), e.toString()));
        }
        report.printSummary(out);
        return report.exitCode(config.allowFailures(), config.failOnWarning());
    }

    private void printPlan(Catalog catalog) {
        out.println("Seeds (" + catalog.entries().size() + "):");
        for (CatalogEntry e : catalog.entries()) {
            out.printf("  %-7s %-30s %s%n", e.kind(), e.alias(), e.gav());
        }
        out.println("Hints (" + catalog.hints().size() + "), sources " + (catalog.includeSources() ? "included" : "excluded") + ":");
        for (ClassifierHint h : catalog.hints()) {
            out.printf("  %s:%s -> %s @ %s%n", h.groupGlob(), h.artifactGlob(), h.classifiers(), h.extension());
        }
    }
}
