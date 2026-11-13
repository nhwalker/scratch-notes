package io.github.nhwalker.offlinecache.mirror;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorUtilsTest {

    @Test
    void generatesOnlyMissingChecksums(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a-1.jar");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        Files.writeString(Checksums.sidecar(file, "sha1"), "upstream-value  a-1.jar");

        List<String> generated = Checksums.writeMissing(file);

        assertEquals(List.of("md5", "sha256", "sha512"), generated);
        assertEquals("upstream-value", Checksums.readSidecar(Checksums.sidecar(file, "sha1")));
        // well-known digests of "hello"
        assertEquals("5d41402abc4b2a76b9719d911017c592", Checksums.readSidecar(Checksums.sidecar(file, "md5")));
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                Checksums.readSidecar(Checksums.sidecar(file, "sha256")));
        assertEquals(List.of(), Checksums.writeMissing(file));
    }

    @Test
    void removableFiles() {
        assertTrue(OutputCleaner.isRemovable("_remote.repositories"));
        assertTrue(OutputCleaner.isRemovable("resolver-status.properties"));
        assertTrue(OutputCleaner.isRemovable("a-1.jar.lastUpdated"));
        assertTrue(OutputCleaner.isRemovable("maven-metadata-nexus-central.xml"));
        assertTrue(OutputCleaner.isRemovable("maven-metadata.xml.sha1"));
        assertTrue(OutputCleaner.isRemovable("a-1-javadoc.jar"));
        assertTrue(OutputCleaner.isRemovable("a-1-javadoc.jar.sha1"));
        assertFalse(OutputCleaner.isRemovable("a-1.jar"));
        assertFalse(OutputCleaner.isRemovable("a-1-sources.jar"));
        assertFalse(OutputCleaner.isRemovable("a-1.pom.sha1"));
        assertFalse(OutputCleaner.isRemovable("a-1.module"));
    }

    @Test
    void cleanRemovesBookkeeping(@TempDir Path dir) throws Exception {
        Path v = Files.createDirectories(dir.resolve("g/a/1"));
        Files.writeString(v.resolve("a-1.jar"), "x");
        Files.writeString(v.resolve("_remote.repositories"), "x");
        Files.writeString(v.resolve("a-1-linux.jar.lastUpdated"), "x");

        assertEquals(2, OutputCleaner.clean(dir).size());
        assertTrue(Files.exists(v.resolve("a-1.jar")));
        assertFalse(Files.exists(v.resolve("_remote.repositories")));
    }
}
