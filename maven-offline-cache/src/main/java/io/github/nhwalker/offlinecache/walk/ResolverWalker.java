package io.github.nhwalker.offlinecache.walk;

import io.github.nhwalker.offlinecache.Gav;
import io.github.nhwalker.offlinecache.RunConfig;
import io.github.nhwalker.offlinecache.catalog.Catalog;
import io.github.nhwalker.offlinecache.report.Issue;
import io.github.nhwalker.offlinecache.report.Report;

import java.util.Set;

/**
 * Maven Resolver implementation of {@link Walker}.
 *
 * <p><b>Scaffold:</b> not implemented yet. It reports a single error so a run can never look
 * successful while producing nothing.
 */
public final class ResolverWalker implements Walker {

    private final RunConfig config;

    public ResolverWalker(RunConfig config) {
        this.config = config;
    }

    @Override
    public Set<Gav> walk(Catalog catalog, Report report) {
        // TODO(design §5): BFS over catalog.entries() using ResolverSessionFactory with
        //  config.outDir() as the local repository; see Walker javadoc for the per-coordinate steps.
        report.add(Issue.error("not-implemented", "walker",
                "dependency walk is not implemented yet (scaffold); " + catalog.entries().size()
                        + " seed(s) not processed, output " + config.outDir() + " not written"));
        return Set.of();
    }
}
