package io.github.nhwalker.offlinecache.catalog;

import io.github.nhwalker.offlinecache.Gav;
import io.github.nhwalker.offlinecache.report.Issue;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a Gradle version catalog ({@code libs.versions.toml}) — design doc §4.
 *
 * <p>Only plain versions are supported; rich versions and ranges are reported as errors.
 * Problems are collected as {@link Issue}s rather than thrown, so one bad entry does not
 * hide the others.
 */
public final class CatalogReader {

    private static final Set<String> LIBRARY_KEYS = Set.of("group", "name", "version", "module");
    private static final Set<String> PLUGIN_KEYS = Set.of("id", "version");
    private static final Set<String> VERSION_KEYS = Set.of("ref", "require", "strictly", "prefer", "reject", "rejectAll");
    private static final Set<String> HINT_KEYS = Set.of("classifiers", "extension");

    private final String source;
    private final List<Issue> issues = new ArrayList<>();
    private final Map<String, String> versions = new LinkedHashMap<>();
    private final Map<String, String> richVersionRefs = new HashMap<>();

    private CatalogReader(String source) {
        this.source = source;
    }

    public static Catalog read(Path file) throws IOException {
        CatalogReader reader = new CatalogReader(file.toString());
        return reader.parse(Toml.parse(file));
    }

    /** For tests: reads TOML from a string. */
    public static Catalog read(String sourceName, String toml) {
        return new CatalogReader(sourceName).parse(Toml.parse(toml));
    }

    private Catalog parse(TomlParseResult toml) {
        if (toml.hasErrors()) {
            for (TomlParseError e : toml.errors()) {
                issues.add(Issue.error("catalog-parse-error", source, e.toString()));
            }
            return new Catalog(List.of(), List.of(), true, issues);
        }

        TomlTable versionsTable = toml.getTable(List.of("versions"));
        if (versionsTable != null) {
            readVersions(versionsTable, "");
        }

        List<CatalogEntry> entries = new ArrayList<>();
        TomlTable libraries = toml.getTable(List.of("libraries"));
        if (libraries != null) {
            readLibraries(libraries, "", entries);
        }
        TomlTable plugins = toml.getTable(List.of("plugins"));
        if (plugins != null) {
            readPlugins(plugins, "", entries);
        }

        boolean includeSources = true;
        List<ClassifierHint> hints = new ArrayList<>();
        TomlTable offlineCache = toml.getTable(List.of("metadata", "offline-cache"));
        if (offlineCache != null) {
            Object inc = offlineCache.get(List.of("include-sources"));
            if (inc instanceof Boolean b) {
                includeSources = b;
            } else if (inc != null) {
                issues.add(Issue.error("hint-invalid", "metadata.offline-cache.include-sources",
                        "must be true or false"));
            }
            TomlTable hintTable = offlineCache.getTable(List.of("hints"));
            if (hintTable != null) {
                readHints(hintTable, entries, hints);
            }
        }
        return new Catalog(entries, hints, includeSources, issues);
    }

    // ---------------------------------------------------------------- versions

    private void readVersions(TomlTable table, String prefix) {
        for (String key : table.keySet()) {
            String alias = prefix + key;
            Object value = table.get(List.of(key));
            if (value instanceof String s) {
                versions.put(alias, s);
            } else if (value instanceof TomlTable t && containsAny(t, VERSION_KEYS)) {
                // Rich version declared in [versions]; remember so refs to it get a precise error.
                richVersionRefs.put(alias, describe(t));
            } else if (value instanceof TomlTable t) {
                readVersions(t, alias + "."); // dotted alias, e.g. kotlin.core = "1.0"
            } else {
                issues.add(Issue.error("catalog-invalid-version", "versions." + alias,
                        "unsupported value " + value));
            }
        }
    }

    // ---------------------------------------------------------------- libraries

    private void readLibraries(TomlTable table, String prefix, List<CatalogEntry> out) {
        for (String key : table.keySet()) {
            String alias = prefix + key;
            Object value = table.get(List.of(key));
            if (value instanceof String s) {
                readLibraryString(alias, s, out);
            } else if (value instanceof TomlTable t && containsAny(t, LIBRARY_KEYS)) {
                readLibraryTable(alias, t, out);
            } else if (value instanceof TomlTable t) {
                readLibraries(t, alias + ".", out); // dotted alias
            } else {
                issues.add(Issue.error("catalog-invalid-entry", "libraries." + alias,
                        "unsupported value " + value));
            }
        }
    }

    private void readLibraryString(String alias, String notation, List<CatalogEntry> out) {
        String[] parts = notation.split(":", -1);
        String subject = "libraries." + alias;
        if (parts.length == 2) {
            noVersion(subject, parts[0] + ":" + parts[1]);
        } else if (parts.length == 3) {
            addLibrary(alias, parts[0], parts[1], parts[2], out);
        } else {
            issues.add(Issue.error("catalog-invalid-entry", subject,
                    "expected 'group:artifact:version' but got '" + notation + "'"));
        }
    }

    private void readLibraryTable(String alias, TomlTable t, List<CatalogEntry> out) {
        String subject = "libraries." + alias;
        if (!checkKeys(t, LIBRARY_KEYS, subject)) {
            return;
        }
        String group;
        String name;
        String module = t.getString(List.of("module"));
        if (module != null) {
            String[] parts = module.split(":", -1);
            if (parts.length != 2) {
                issues.add(Issue.error("catalog-invalid-entry", subject,
                        "module must be 'group:artifact' but got '" + module + "'"));
                return;
            }
            group = parts[0];
            name = parts[1];
        } else {
            group = t.getString(List.of("group"));
            name = t.getString(List.of("name"));
            if (group == null || name == null) {
                issues.add(Issue.error("catalog-invalid-entry", subject,
                        "needs either 'module' or both 'group' and 'name'"));
                return;
            }
        }
        String version = resolveVersion(t.get(List.of("version")), subject);
        if (version == null) {
            if (!t.contains(List.of("version"))) {
                noVersion(subject, group + ":" + name);
            }
            return; // resolveVersion already reported the problem
        }
        addLibrary(alias, group, name, version, out);
    }

    private void addLibrary(String alias, String group, String name, String version, List<CatalogEntry> out) {
        String subject = "libraries." + alias;
        if (!isPlainVersion(version)) {
            notPlain(subject, version);
            return;
        }
        out.add(new CatalogEntry(alias, CatalogEntry.Kind.LIBRARY, new Gav(group, name, version)));
    }

    private void noVersion(String subject, String module) {
        issues.add(Issue.warning("catalog-no-version", subject,
                module + " has no version (expected from a BOM at build time); skipped"));
    }

    // ---------------------------------------------------------------- plugins

    private void readPlugins(TomlTable table, String prefix, List<CatalogEntry> out) {
        for (String key : table.keySet()) {
            String alias = prefix + key;
            String subject = "plugins." + alias;
            Object value = table.get(List.of(key));
            if (value instanceof String s) {
                int colon = s.lastIndexOf(':');
                if (colon <= 0 || colon == s.length() - 1) {
                    issues.add(Issue.error("catalog-invalid-entry", subject,
                            "expected 'plugin.id:version' but got '" + s + "'"));
                } else {
                    addPlugin(alias, s.substring(0, colon), s.substring(colon + 1), out);
                }
            } else if (value instanceof TomlTable t && containsAny(t, PLUGIN_KEYS)) {
                if (!checkKeys(t, PLUGIN_KEYS, subject)) {
                    continue;
                }
                String id = t.getString(List.of("id"));
                if (id == null) {
                    issues.add(Issue.error("catalog-invalid-entry", subject, "missing 'id'"));
                    continue;
                }
                String version = resolveVersion(t.get(List.of("version")), subject);
                if (version == null) {
                    if (!t.contains(List.of("version"))) {
                        issues.add(Issue.warning("catalog-no-version", subject,
                                "plugin " + id + " has no version; skipped"));
                    }
                    continue;
                }
                addPlugin(alias, id, version, out);
            } else if (value instanceof TomlTable t) {
                readPlugins(t, alias + ".", out); // dotted alias
            } else {
                issues.add(Issue.error("catalog-invalid-entry", subject, "unsupported value " + value));
            }
        }
    }

    private void addPlugin(String alias, String id, String version, List<CatalogEntry> out) {
        if (!isPlainVersion(version)) {
            notPlain("plugins." + alias, version);
            return;
        }
        out.add(new CatalogEntry(alias, CatalogEntry.Kind.PLUGIN, CatalogEntry.pluginMarker(id, version)));
    }

    // ---------------------------------------------------------------- versions (shared)

    /**
     * Resolves a {@code version} value: a string, or a table with {@code ref}.
     * Returns null (after reporting) when absent, unknown or rich.
     */
    private String resolveVersion(Object value, String subject) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof TomlTable t) {
            if (t.keySet().equals(Set.of("ref"))) {
                String ref = t.getString(List.of("ref"));
                if (versions.containsKey(ref)) {
                    return versions.get(ref);
                }
                if (richVersionRefs.containsKey(ref)) {
                    issues.add(Issue.error("catalog-rich-version", subject,
                            "version.ref '" + ref + "' is a rich version " + richVersionRefs.get(ref)
                                    + "; only plain versions are supported"));
                } else {
                    issues.add(Issue.error("catalog-unknown-version-ref", subject,
                            "version.ref '" + ref + "' is not defined in [versions]"));
                }
                return null;
            }
            if (checkKeys(t, VERSION_KEYS, subject)) {
                issues.add(Issue.error("catalog-rich-version", subject,
                        "rich version " + describe(t) + "; only plain versions are supported"));
            }
            return null;
        }
        issues.add(Issue.error("catalog-invalid-version", subject, "unsupported version value " + value));
        return null;
    }

    /** Plain = a fixed version: no ranges, no dynamic {@code +}, no {@code latest.*}. */
    static boolean isPlainVersion(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        if (version.startsWith("latest.") || version.endsWith("+")) {
            return false;
        }
        for (char c : version.toCharArray()) {
            if (c == '[' || c == ']' || c == '(' || c == ')' || c == ',' || Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    private void notPlain(String subject, String version) {
        issues.add(Issue.error("catalog-rich-version", subject,
                "version '" + version + "' is a range or dynamic version; only plain versions are supported"));
    }

    // ---------------------------------------------------------------- hints

    private void readHints(TomlTable table, List<CatalogEntry> entries, List<ClassifierHint> out) {
        for (String key : table.keySet()) {
            String subject = "metadata.offline-cache.hints." + key;
            Object value = table.get(List.of(key));
            if (!(value instanceof TomlTable t)) {
                issues.add(Issue.error("hint-invalid", subject,
                        "expected a table like { classifiers = [\"linux\"], extension = \"jar\" }"));
                continue;
            }
            if (!checkKeys(t, HINT_KEYS, subject)) {
                continue;
            }
            TomlArray array = t.getArray(List.of("classifiers"));
            if (array == null || array.isEmpty()) {
                issues.add(Issue.error("hint-invalid", subject, "'classifiers' must be a non-empty array"));
                continue;
            }
            List<String> classifiers = new ArrayList<>();
            boolean ok = true;
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i) instanceof String s && !s.isBlank()) {
                    classifiers.add(s);
                } else {
                    ok = false;
                }
            }
            Object ext = t.get(List.of("extension"));
            if (ext != null && !(ext instanceof String)) {
                ok = false;
            }
            if (!ok) {
                issues.add(Issue.error("hint-invalid", subject,
                        "'classifiers' must contain non-blank strings and 'extension' must be a string"));
                continue;
            }
            String extension = (String) ext;

            int colon = key.indexOf(':');
            if (colon >= 0) {
                if (colon == 0 || colon == key.length() - 1 || key.indexOf(':', colon + 1) >= 0) {
                    issues.add(Issue.error("hint-invalid", subject, "key must be an alias or 'group:artifact'"));
                    continue;
                }
                out.add(new ClassifierHint(key, key.substring(0, colon), key.substring(colon + 1),
                        classifiers, extension, source));
            } else {
                CatalogEntry entry = findAlias(entries, key);
                if (entry == null) {
                    issues.add(Issue.warning("hint-unknown-alias", subject,
                            "no [libraries] or [plugins] alias '" + key + "' in this catalog; hint ignored"));
                    continue;
                }
                out.add(new ClassifierHint(key, entry.gav().groupId(), entry.gav().artifactId(),
                        classifiers, extension, source));
            }
        }
    }

    /** Gradle treats {@code -}, {@code _} and {@code .} in aliases as equivalent separators. */
    private static CatalogEntry findAlias(List<CatalogEntry> entries, String alias) {
        String wanted = normalizeAlias(alias);
        return entries.stream()
                .filter(e -> normalizeAlias(e.alias()).equals(wanted))
                .findFirst()
                .orElse(null);
    }

    private static String normalizeAlias(String alias) {
        return alias.replace('-', '.').replace('_', '.');
    }

    // ---------------------------------------------------------------- helpers

    private static boolean containsAny(TomlTable t, Set<String> keys) {
        return t.keySet().stream().anyMatch(keys::contains);
    }

    private boolean checkKeys(TomlTable t, Set<String> allowed, String subject) {
        List<String> unexpected = t.keySet().stream().filter(k -> !allowed.contains(k)).sorted().toList();
        if (!unexpected.isEmpty()) {
            issues.add(Issue.error("catalog-unexpected-key", subject,
                    "unexpected key(s) " + unexpected + "; allowed: " + allowed.stream().sorted().toList()));
            return false;
        }
        return true;
    }

    private static String describe(TomlTable t) {
        return t.toJson().replaceAll("\\s+", " ").trim();
    }
}
