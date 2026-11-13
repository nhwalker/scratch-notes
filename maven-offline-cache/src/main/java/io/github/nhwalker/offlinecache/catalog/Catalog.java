package io.github.nhwalker.offlinecache.catalog;

import io.github.nhwalker.offlinecache.report.Issue;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of reading one or more version catalogs.
 *
 * @param entries        seed coordinates (libraries and plugin markers)
 * @param hints          classifier/extension hints from {@code [metadata.offline-cache.hints]}
 * @param includeSources whether {@code sources} jars are probed for every coordinate
 * @param issues         problems found while reading
 */
public record Catalog(List<CatalogEntry> entries, List<ClassifierHint> hints,
                      boolean includeSources, List<Issue> issues) {

    public Catalog {
        entries = List.copyOf(entries);
        hints = List.copyOf(hints);
        issues = List.copyOf(issues);
    }

    /**
     * Merges catalogs (design doc §4.1/§4.2): entries and hints are unioned;
     * sources are included if any catalog includes them.
     */
    public static Catalog merge(List<Catalog> catalogs) {
        List<CatalogEntry> entries = new ArrayList<>();
        List<ClassifierHint> hints = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        boolean includeSources = catalogs.isEmpty();
        for (Catalog c : catalogs) {
            entries.addAll(c.entries());
            hints.addAll(c.hints());
            issues.addAll(c.issues());
            includeSources |= c.includeSources();
        }
        return new Catalog(entries, hints, includeSources, issues);
    }
}
