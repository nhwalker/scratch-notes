package io.github.nhwalker.offlinecache.walk;

import io.github.nhwalker.offlinecache.Gav;
import io.github.nhwalker.offlinecache.RunConfig;
import io.github.nhwalker.offlinecache.catalog.Catalog;
import io.github.nhwalker.offlinecache.catalog.CatalogReader;
import io.github.nhwalker.offlinecache.report.Issue;
import io.github.nhwalker.offlinecache.report.Report;
import io.github.nhwalker.offlinecache.report.Severity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Walker behaviour against an in-process repository (design doc §5, §8, §9). No network. */
class ResolverWalkerTest {

    @TempDir
    Path tmp;

    private FakeRepository repo;
    private Path out;

    @BeforeEach
    void setUp() throws Exception {
        repo = new FakeRepository(Files.createDirectories(tmp.resolve("remote")));
        out = tmp.resolve("out");
    }

    @AfterEach
    void tearDown() {
        repo.close();
    }

    @Test
    void parentsBomsAndEveryVersionAreKept() throws Exception {
        repo.pom("org.ex:parent:1", "<packaging>pom</packaging>"
                        + "<dependencyManagement><dependencies><dependency><groupId>org.ex</groupId>"
                        + "<artifactId>bom</artifactId><version>1</version><type>pom</type><scope>import</scope>"
                        + "</dependency></dependencies></dependencyManagement>")
                .pom("org.ex:bom:1", "<packaging>pom</packaging><dependencyManagement><dependencies>"
                        + "<dependency><groupId>org.ex</groupId><artifactId>c</artifactId><version>1</version></dependency>"
                        + "</dependencies></dependencyManagement>")
                // a: version of c comes from the imported BOM (1)
                .pom("org.ex:a:1", "<parent><groupId>org.ex</groupId><artifactId>parent</artifactId><version>1</version></parent>"
                        + deps(dep("org.ex", "c", null)))
                .file("org.ex:a:1", "", "jar")
                // b: asks for c:2 → Maven would mediate; we keep both
                .pom("org.ex:b:1", deps(dep("org.ex", "c", "2")
                        + dep("org.ex", "t", "1", "<scope>test</scope>")
                        + dep("org.ex", "o", "1", "<optional>true</optional>")))
                .file("org.ex:b:1", "", "jar")
                .pom("org.ex:c:1", "").file("org.ex:c:1", "", "jar")
                .pom("org.ex:c:2", "").file("org.ex:c:2", "", "jar").file("org.ex:c:2", "sources", "jar");

        Result r = walk("""
                [libraries]
                a = "org.ex:a:1"
                b = "org.ex:b:1"
                """);

        assertEquals(List.of(), r.codes(Severity.ERROR));
        assertEquals(Set.of("org.ex:a:1", "org.ex:b:1", "org.ex:c:1", "org.ex:c:2"), r.visitedStrings());
        assertExists("org.ex:parent:1", "", "pom");   // parent POM saved by Resolver
        assertExists("org.ex:bom:1", "", "pom");      // imported BOM saved by Resolver
        assertExists("org.ex:c:1", "", "jar");
        assertExists("org.ex:c:2", "", "jar");
        assertExists("org.ex:c:2", "sources", "jar");
        assertFalse(Files.exists(out.resolve(FakeRepository.path("org.ex:t:1", "", "pom")))); // test scope
        assertFalse(Files.exists(out.resolve(FakeRepository.path("org.ex:o:1", "", "pom")))); // optional
        assertTrue(r.codes(Severity.INFO).contains("no-sources"));
    }

    @Test
    void hintsProbeClassifiersAndExtensions() throws Exception {
        repo.pom("com.ex:protoc:3", "<packaging>pom</packaging>")
                .file("com.ex:protoc:3", "linux-x86_64", "exe")
                .file("com.ex:protoc:3", "windows-x86_64", "exe")
                .pom("com.ex:fx-base:21", "").file("com.ex:fx-base:21", "", "jar")
                .file("com.ex:fx-base:21", "linux", "jar");

        Result r = walk("""
                [metadata.offline-cache.hints]
                "com.ex:protoc" = { classifiers = ["linux-x86_64", "windows-x86_64", "osx-x86_64"], extension = "exe" }
                "com.ex:fx-*" = { classifiers = ["linux", "javadoc"] }
                "com.ex:nothing-*" = { classifiers = ["x"] }
                fx = { classifiers = ["mac"] }
                [libraries]
                protoc = "com.ex:protoc:3"
                fx = "com.ex:fx-base:21"
                """);

        assertEquals(List.of(), r.codes(Severity.ERROR));
        assertExists("com.ex:protoc:3", "linux-x86_64", "exe");
        assertExists("com.ex:protoc:3", "windows-x86_64", "exe");
        assertExists("com.ex:fx-base:21", "linux", "jar");
        assertTrue(r.has(Severity.INFO, "pom-only", "com.ex:protoc:3"));
        assertTrue(r.has(Severity.INFO, "hint-classifier-missing", "com.ex:protoc:3")); // osx missing
        assertTrue(r.has(Severity.WARNING, "hint-no-classifiers", "com.ex:fx-base:21"));  // alias hint: mac missing
        assertTrue(r.has(Severity.WARNING, "hint-unmatched", "com.ex:nothing-*"));
        assertFalse(Files.exists(out.resolve(FakeRepository.path("com.ex:fx-base:21", "javadoc", "jar"))));
    }

    @Test
    void pomClassifierDependenciesAreRequired() throws Exception {
        repo.pom("io.ex:native:2", deps(
                        dep("io.ex", "native", "2", "<classifier>linux-x86_64</classifier>")
                                + dep("io.ex", "native", "2", "<classifier>windows-x86_64</classifier>")))
                .file("io.ex:native:2", "", "jar")
                .file("io.ex:native:2", "linux-x86_64", "jar");

        Result r = walk("[libraries]\nn = \"io.ex:native:2\"\n");

        assertExists("io.ex:native:2", "linux-x86_64", "jar");
        assertEquals(List.of("classifier-dependency-missing"), r.codes(Severity.ERROR));
    }

    @Test
    void gradleModuleFilesVariantsAvailableAtAndConstraints() throws Exception {
        String marker = "<!-- do_not_remove: published-with-gradle-metadata -->";
        repo.pom("org.k:plugin:2", marker + deps(dep("org.k", "stdlib", "2")))
                .file("org.k:plugin:2", "", "jar")
                .file("org.k:plugin:2", "gradle85", "jar")
                .file("org.k:plugin:2", "gradle85-javadoc", "jar")
                .file("org.k:plugin:2", "", "module", """
                        {"formatVersion":"1.1","variants":[
                          {"name":"runtime","attributes":{},
                           "files":[{"name":"plugin-2.jar","url":"plugin-2.jar"}],
                           "dependencies":[{"group":"org.k","module":"api","version":{"requires":"2"}}],
                           "dependencyConstraints":[{"group":"org.k","module":"stdlib","version":{"requires":"3"}},
                                                    {"group":"org.k","module":"unused","version":{"requires":"9"}}]},
                          {"name":"gradle85Runtime","attributes":{"org.gradle.plugin.api-version":"8.5"},
                           "files":[{"name":"plugin-2-gradle85.jar","url":"plugin-2-gradle85.jar"}]},
                          {"name":"gradle85Javadoc","attributes":{},
                           "files":[{"name":"plugin-2-gradle85-javadoc.jar","url":"plugin-2-gradle85-javadoc.jar"}]},
                          {"name":"jvm","attributes":{"org.jetbrains.kotlin.platform.type":"jvm"},
                           "available-at":{"url":"../../lib-jvm/2/lib-jvm-2.module","group":"org.k","module":"lib-jvm","version":"2"}},
                          {"name":"js","attributes":{"org.jetbrains.kotlin.platform.type":"js"},
                           "available-at":{"url":"../../lib-js/2/lib-js-2.module","group":"org.k","module":"lib-js","version":"2"}}
                        ]}""")
                .pom("org.k:api:2", "").file("org.k:api:2", "", "jar")
                .pom("org.k:stdlib:2", "").file("org.k:stdlib:2", "", "jar")
                .pom("org.k:stdlib:3", "").file("org.k:stdlib:3", "", "jar")
                .pom("org.k:lib-jvm:2", "").file("org.k:lib-jvm:2", "", "jar");

        Result r = walkSeeds(List.of(Gav.parse("org.k:plugin:2")));

        assertEquals(List.of(), r.codes(Severity.ERROR));
        assertExists("org.k:plugin:2", "gradle85", "jar");
        assertExists("org.k:plugin:2", "", "module");
        assertFalse(Files.exists(out.resolve(FakeRepository.path("org.k:plugin:2", "gradle85-javadoc", "jar"))));
        assertTrue(r.visitedStrings().containsAll(Set.of("org.k:api:2", "org.k:lib-jvm:2", "org.k:stdlib:3")));
        assertFalse(r.visitedStrings().contains("org.k:lib-js:2"));   // non-JVM variant skipped
        assertFalse(r.visitedStrings().contains("org.k:unused:9"));  // constraint for module not in graph
        assertTrue(r.has(Severity.INFO, "module-variant-skipped", "org.k:plugin:2"));
    }

    @Test
    void missingPomAndMainArtifactAreErrors() throws Exception {
        repo.pom("org.ex:a:1", deps(dep("org.ex", "gone", "1"))); // jar packaging, but no jar

        Result r = walk("[libraries]\na = \"org.ex:a:1\"\n");

        assertTrue(r.has(Severity.ERROR, "main-artifact-missing", "org.ex:a:1"));
        assertTrue(r.has(Severity.ERROR, "pom-not-found", "org.ex:gone:1"));
    }

    @Test
    void missingParentIsPomInvalid() throws Exception {
        repo.pom("org.ex:a:1", "<parent><groupId>org.ex</groupId><artifactId>nope</artifactId><version>1</version></parent>")
                .file("org.ex:a:1", "", "jar");

        Result r = walk("[libraries]\na = \"org.ex:a:1\"\n");

        assertTrue(r.has(Severity.ERROR, "pom-invalid", "org.ex:a:1"));
    }

    @Test
    void checksumMismatchIsErrorAndMissingChecksumIsAccepted() throws Exception {
        repo.pom("org.ex:a:1", "")
                .file("org.ex:a:1", "", "jar", "jar".getBytes(StandardCharsets.UTF_8), FakeRepository.Sha1.WRONG)
                .file("org.ex:a:1", "sources", "jar", "src".getBytes(StandardCharsets.UTF_8), FakeRepository.Sha1.NONE);

        Result r = walk("[libraries]\na = \"org.ex:a:1\"\n");

        // Reported once, although Resolver retries the corrupted download.
        assertEquals(List.of("checksum-mismatch"), r.codes(Severity.ERROR));
        // No upstream checksum: Resolver's warn policy accepts the file without an event (verified behaviour).
        assertExists("org.ex:a:1", "sources", "jar");
    }

    @Test
    void relocationIsFollowedWithWarning() throws Exception {
        repo.pom("old.g:lib:1", "<distributionManagement><relocation><groupId>new.g</groupId>"
                        + "</relocation></distributionManagement>")
                .pom("new.g:lib:1", "").file("new.g:lib:1", "", "jar");

        Result r = walk("[libraries]\nlib = \"old.g:lib:1\"\n");

        assertTrue(r.has(Severity.WARNING, "relocated", "old.g:lib:1"));
        assertExists("new.g:lib:1", "", "jar");
        assertEquals(List.of(), r.codes(Severity.ERROR));
    }

    @Test
    void versionRangeInPomResolvesToHighest() throws Exception {
        repo.pom("org.ex:a:1", deps(dep("org.ex", "r", "[1.0,2.0)"))).file("org.ex:a:1", "", "jar")
                .pom("org.ex:r:1.0", "").file("org.ex:r:1.0", "", "jar")
                .pom("org.ex:r:1.5", "").file("org.ex:r:1.5", "", "jar")
                .pom("org.ex:r:2.0", "").file("org.ex:r:2.0", "", "jar");
        Path meta = tmp.resolve("remote/org/ex/r/maven-metadata.xml");
        Files.writeString(meta, """
                <metadata><groupId>org.ex</groupId><artifactId>r</artifactId><versioning>
                <versions><version>1.0</version><version>1.5</version><version>2.0</version></versions>
                </versioning></metadata>""");

        Result r = walk("[libraries]\na = \"org.ex:a:1\"\n");

        assertTrue(r.visitedStrings().contains("org.ex:r:1.5"));
        assertFalse(r.visitedStrings().contains("org.ex:r:2.0"));
        assertTrue(r.has(Severity.INFO, "version-range-resolved", "org.ex:r:[1.0,2.0)"));
    }

    @Test
    void rerunReusesExistingFiles() throws Exception {
        repo.pom("org.ex:a:1", "").file("org.ex:a:1", "", "jar").file("org.ex:a:1", "sources", "jar");
        String catalog = "[libraries]\na = \"org.ex:a:1\"\n";
        walk(catalog);
        io.github.nhwalker.offlinecache.mirror.OutputCleaner.clean(out); // as a real run does

        Result second = walk(catalog);

        assertEquals(List.of(), second.codes(Severity.ERROR));
        assertTrue(second.has(Severity.INFO, "already-present", FakeRepository.path("org.ex:a:1", "", "jar")));
    }

    // ------------------------------------------------------------------ helpers

    private record Result(Set<Gav> visited, Report report) {
        List<String> codes(Severity s) {
            return report.issues().stream().filter(i -> i.severity() == s).map(Issue::code).toList();
        }

        boolean has(Severity s, String code, String subject) {
            return report.issues().stream().anyMatch(i -> i.severity() == s && i.code().equals(code)
                    && i.subject().equals(subject));
        }

        Set<String> visitedStrings() {
            return visited.stream().map(Gav::toString).collect(java.util.stream.Collectors.toSet());
        }
    }

    private Result walk(String toml) {
        Catalog catalog = CatalogReader.read("test", toml);
        assertEquals(List.of(), catalog.issues(), "test catalog must be valid");
        Report report = new Report();
        Set<Gav> visited = new ResolverWalker(config()).walk(catalog, report);
        return new Result(visited, report);
    }

    private Result walkSeeds(List<Gav> seeds) {
        List<io.github.nhwalker.offlinecache.catalog.CatalogEntry> entries = seeds.stream()
                .map(g -> new io.github.nhwalker.offlinecache.catalog.CatalogEntry(g.artifactId(),
                        io.github.nhwalker.offlinecache.catalog.CatalogEntry.Kind.LIBRARY, g))
                .toList();
        Report report = new Report();
        Set<Gav> visited = new ResolverWalker(config())
                .walk(new Catalog(entries, List.of(), true, List.of()), report);
        return new Result(visited, report);
    }

    private RunConfig config() {
        return new RunConfig(List.of(), out, tmp.resolve("report.json"), repo.url(), null,
                null, null, 2, 0, false, false, false);
    }

    private void assertExists(String gav, String classifier, String extension) {
        Path p = out.resolve(FakeRepository.path(gav, classifier, extension));
        assertTrue(Files.isRegularFile(p), "expected " + p);
    }

    private static String deps(String inner) {
        return "<dependencies>" + inner + "</dependencies>";
    }

    private static String dep(String g, String a, String v) {
        return dep(g, a, v, "");
    }

    private static String dep(String g, String a, String v, String extra) {
        return "<dependency><groupId>" + g + "</groupId><artifactId>" + a + "</artifactId>"
                + (v == null ? "" : "<version>" + v + "</version>") + extra + "</dependency>";
    }
}
