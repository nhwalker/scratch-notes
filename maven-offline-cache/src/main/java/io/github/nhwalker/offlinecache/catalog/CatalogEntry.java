package io.github.nhwalker.offlinecache.catalog;

import io.github.nhwalker.offlinecache.Gav;

/**
 * A seed coordinate taken from a version catalog.
 *
 * @param alias catalog alias as written (e.g. {@code javafx-controls}, {@code kotlin.jvm})
 * @param kind  library or plugin
 * @param gav   for libraries the library itself; for plugins the plugin marker
 *              {@code id:id.gradle.plugin:version}
 */
public record CatalogEntry(String alias, Kind kind, Gav gav) {

    public enum Kind {
        LIBRARY,
        PLUGIN
    }

    /** Gradle plugin marker coordinate for a plugin id and version. */
    public static Gav pluginMarker(String pluginId, String version) {
        return new Gav(pluginId, pluginId + ".gradle.plugin", version);
    }
}
