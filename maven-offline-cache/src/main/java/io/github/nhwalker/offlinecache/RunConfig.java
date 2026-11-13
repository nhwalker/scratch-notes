package io.github.nhwalker.offlinecache;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Everything a run needs, after CLI parsing and env-var fallback (design doc §6, §7).
 *
 * @param catalogs       version catalog files (1..n)
 * @param outDir         output directory (Maven layout)
 * @param reportFile     where report.json is written
 * @param centralUrl     Nexus proxy for Maven Central
 * @param pluginsUrl     Nexus proxy for the Gradle Plugin Portal (may be null: plugins then use centralUrl only)
 * @param username       Nexus username from {@code NEXUS_USERNAME} (may be null for anonymous)
 * @param password       Nexus password from {@code NEXUS_PASSWORD} (may be null)
 * @param parallelism    parallel downloads
 * @param retries        retries per download
 * @param allowFailures  {@code --allow-failures}
 * @param failOnWarning  {@code --fail-on-warning}
 * @param dryRun         only read catalogs and print the seeds/hints
 */
public record RunConfig(List<Path> catalogs, Path outDir, Path reportFile,
                        String centralUrl, String pluginsUrl,
                        String username, String password,
                        int parallelism, int retries,
                        boolean allowFailures, boolean failOnWarning, boolean dryRun) {

    public RunConfig {
        catalogs = List.copyOf(catalogs);
        Objects.requireNonNull(outDir, "outDir");
        Objects.requireNonNull(reportFile, "reportFile");
        if (parallelism < 1) throw new IllegalArgumentException("parallelism must be >= 1");
        if (retries < 0) throw new IllegalArgumentException("retries must be >= 0");
    }

    /** Never prints the password. */
    @Override
    public String toString() {
        return "RunConfig[catalogs=" + catalogs + ", outDir=" + outDir + ", reportFile=" + reportFile
                + ", centralUrl=" + centralUrl + ", pluginsUrl=" + pluginsUrl
                + ", username=" + (username == null ? "<none>" : username)
                + ", password=" + (password == null ? "<none>" : "****")
                + ", parallelism=" + parallelism + ", retries=" + retries
                + ", allowFailures=" + allowFailures + ", failOnWarning=" + failOnWarning
                + ", dryRun=" + dryRun + "]";
    }
}
