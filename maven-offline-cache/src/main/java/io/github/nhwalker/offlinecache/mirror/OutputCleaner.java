package io.github.nhwalker.offlinecache.mirror;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Final cleanup of the output directory (design doc §3, §7): removes Maven Resolver bookkeeping
 * files and excluded files, leaving a plain Maven-layout repository.
 */
public final class OutputCleaner {

    private OutputCleaner() {
    }

    /** True for files that must not remain in the output. */
    public static boolean isRemovable(String fileName) {
        return fileName.equals("_remote.repositories")
                || fileName.equals("resolver-status.properties")
                || fileName.endsWith(".lastUpdated")
                || fileName.startsWith("maven-metadata") && (fileName.endsWith(".xml") || fileName.contains(".xml."))
                || isJavadoc(fileName);
    }

    /** {@code *-javadoc.jar} and its checksum/signature sidecars. */
    static boolean isJavadoc(String fileName) {
        return fileName.endsWith("-javadoc.jar") || fileName.contains("-javadoc.jar.");
    }

    /** @return files removed */
    public static List<Path> clean(Path outDir) throws IOException {
        if (!Files.isDirectory(outDir)) {
            return List.of();
        }
        List<Path> removable;
        try (Stream<Path> files = Files.walk(outDir)) {
            removable = files.filter(Files::isRegularFile)
                    .filter(p -> isRemovable(p.getFileName().toString()))
                    .toList();
        }
        for (Path p : removable) {
            Files.deleteIfExists(p);
        }
        return removable;
    }
}
