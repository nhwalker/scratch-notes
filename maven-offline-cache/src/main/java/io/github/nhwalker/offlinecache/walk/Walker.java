package io.github.nhwalker.offlinecache.walk;

import io.github.nhwalker.offlinecache.Gav;
import io.github.nhwalker.offlinecache.catalog.Catalog;
import io.github.nhwalker.offlinecache.report.Report;

import java.util.Set;

/**
 * Breadth-first dependency walk that keeps every requested version (design doc §5).
 *
 * <p>Planned per-coordinate steps:
 * <ol>
 *   <li>{@code readArtifactDescriptor}: POM dependencies in compile/runtime scope
 *       (not test/provided/optional); parents and imported BOMs are saved by Resolver;
 *       relocations are followed (warning).</li>
 *   <li>{@code .module} (if present): dependencies and files of all variants, plus
 *       constraint versions for modules already in the graph.</li>
 *   <li>Main artifact unless packaging is {@code pom}; POM-declared classifier dependencies.</li>
 *   <li>Hint probes (classifiers × extension) and {@code sources}.</li>
 * </ol>
 */
public interface Walker {

    /**
     * Walks the graph from the catalog seeds and downloads everything into the output directory.
     *
     * @return every coordinate visited
     */
    Set<Gav> walk(Catalog catalog, Report report);
}
