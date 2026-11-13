package io.github.nhwalker.offlinecache.catalog;

import io.github.nhwalker.offlinecache.Gav;
import io.github.nhwalker.offlinecache.report.Issue;
import io.github.nhwalker.offlinecache.report.Severity;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogReaderTest {

    @Test
    void readsSampleCatalog() throws Exception {
        Catalog catalog = CatalogReader.read(resource("catalogs/sample.versions.toml"));

        assertEquals(List.of(), catalog.issues());
        Map<String, Gav> byAlias = catalog.entries().stream()
                .collect(Collectors.toMap(CatalogEntry::alias, CatalogEntry::gav));
        assertEquals(Gav.parse("org.openjfx:javafx-controls:21"), byAlias.get("javafx-controls"));
        assertEquals(Gav.parse("com.google.protobuf:protobuf-java:3.25.1"), byAlias.get("protobuf-java"));
        assertEquals(Gav.parse("com.google.protobuf:protoc:3.25.1"), byAlias.get("protoc"));
        assertEquals(Gav.parse("io.netty:netty-tcnative-boringssl-static:2.0.65.Final"), byAlias.get("netty-tcnative"));
        assertEquals(Gav.parse("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:2.0.0"),
                byAlias.get("kotlin-jvm"));
        assertTrue(catalog.includeSources());

        assertEquals(3, catalog.hints().size());
        ClassifierHint protoc = hint(catalog, "com.google.protobuf:protoc");
        assertEquals(List.of("linux-x86_64", "windows-x86_64", "osx-x86_64"), protoc.classifiers());
        assertEquals("exe", protoc.extension());

        ClassifierHint javafx = hint(catalog, "org.openjfx:javafx-*");
        assertEquals("jar", javafx.extension());
        assertTrue(javafx.matches("org.openjfx", "javafx-graphics"));
        assertFalse(javafx.matches("org.openjfx", "javafx")); // parent POM, no dash

        // alias-keyed hint is converted to the alias's coordinates
        ClassifierHint grpc = hint(catalog, "protoc-gen-grpc-java");
        assertEquals("io.grpc", grpc.groupGlob());
        assertEquals("protoc-gen-grpc-java", grpc.artifactGlob());
    }

    @Test
    void dottedAliasesAreFlattened() {
        Catalog catalog = CatalogReader.read("t", """
                [versions]
                kotlin.core = "2.0.0"
                [libraries]
                kotlin.stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin.core" }
                [plugins]
                kotlin.jvm = "org.jetbrains.kotlin.jvm:2.0.0"
                """);
        assertEquals(List.of(), catalog.issues());
        assertEquals(List.of("kotlin.stdlib", "kotlin.jvm"),
                catalog.entries().stream().map(CatalogEntry::alias).toList());
        assertEquals("2.0.0", catalog.entries().get(0).gav().version());
    }

    @Test
    void missingVersionIsWarningAndSkipped() {
        Catalog catalog = CatalogReader.read("t", """
                [libraries]
                a = "g:a"
                b = { module = "g:b" }
                """);
        assertTrue(catalog.entries().isEmpty());
        assertEquals(List.of("catalog-no-version", "catalog-no-version"), codes(catalog, Severity.WARNING));
    }

    @Test
    void richVersionsAndRangesAreErrors() {
        Catalog catalog = CatalogReader.read("t", """
                [versions]
                rich = { strictly = "1.0" }
                [libraries]
                a = { module = "g:a", version = { require = "1.0" } }
                b = { module = "g:b", version.ref = "rich" }
                c = "g:c:[1.0,2.0)"
                d = "g:d:1.+"
                e = "g:e:latest.release"
                f = { module = "g:f", version.ref = "missing" }
                """);
        assertTrue(catalog.entries().isEmpty());
        assertEquals(List.of("catalog-rich-version", "catalog-rich-version", "catalog-rich-version",
                        "catalog-rich-version", "catalog-rich-version", "catalog-unknown-version-ref"),
                codes(catalog, Severity.ERROR));
    }

    @Test
    void unexpectedKeysAreErrors() {
        Catalog catalog = CatalogReader.read("t", """
                [libraries]
                a = { module = "g:a", version = "1", classifier = "linux" }
                """);
        assertTrue(catalog.entries().isEmpty());
        assertEquals(List.of("catalog-unexpected-key"), codes(catalog, Severity.ERROR));
    }

    @Test
    void invalidHintsAreReported() {
        Catalog catalog = CatalogReader.read("t", """
                [metadata.offline-cache]
                include-sources = false
                [metadata.offline-cache.hints]
                "g:a" = { classifiers = [] }
                "g:b" = { classifiers = ["x"], other = 1 }
                unknown-alias = { classifiers = ["x"] }
                "g:c" = "not a table"
                [libraries]
                a = "g:a:1"
                """);
        assertFalse(catalog.includeSources());
        assertTrue(catalog.hints().isEmpty());
        assertEquals(List.of("hint-invalid", "catalog-unexpected-key", "hint-invalid"),
                codes(catalog, Severity.ERROR));
        assertEquals(List.of("hint-unknown-alias"), codes(catalog, Severity.WARNING));
    }

    @Test
    void syntaxErrorIsReported() {
        Catalog catalog = CatalogReader.read("t", "[libraries\n");
        assertEquals(List.of("catalog-parse-error"), codes(catalog, Severity.ERROR).stream().distinct().toList());
    }

    @Test
    void plainVersionCheck() {
        assertTrue(CatalogReader.isPlainVersion("1.0"));
        assertTrue(CatalogReader.isPlainVersion("2.0.65.Final"));
        assertTrue(CatalogReader.isPlainVersion("33.0-jre"));
        assertFalse(CatalogReader.isPlainVersion("1.+"));
        assertFalse(CatalogReader.isPlainVersion("[1.0,)"));
        assertFalse(CatalogReader.isPlainVersion("latest.integration"));
        assertFalse(CatalogReader.isPlainVersion(""));
    }

    private static ClassifierHint hint(Catalog catalog, String key) {
        return catalog.hints().stream().filter(h -> h.key().equals(key)).findFirst().orElseThrow();
    }

    private static List<String> codes(Catalog catalog, Severity severity) {
        return catalog.issues().stream().filter(i -> i.severity() == severity).map(Issue::code).toList();
    }

    private static Path resource(String name) throws URISyntaxException {
        return Path.of(CatalogReaderTest.class.getClassLoader().getResource(name).toURI());
    }
}
