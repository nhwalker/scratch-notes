package io.github.nhwalker.offlinecache.walk;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Facts read from the raw POM file (not the effective model).
 *
 * @param packaging        {@code <packaging>}, default {@code jar} (packaging is never inherited)
 * @param gradleMetadata   POM carries Gradle's {@code published-with-gradle-metadata} marker,
 *                         meaning a {@code .module} file was published next to it
 */
public record PomInfo(String packaging, boolean gradleMetadata) {

    /** Marker comment Gradle writes into POMs when it also publishes a {@code .module} file. */
    static final String GRADLE_METADATA_MARKER = "published-with-gradle-metadata";

    private static final Map<String, String> PACKAGING_EXTENSIONS = Map.of(
            "jar", "jar",
            "bundle", "jar",
            "maven-plugin", "jar",
            "ejb", "jar",
            "eclipse-plugin", "jar",
            "java-source", "jar",
            "javadoc", "jar",
            "test-jar", "jar");

    public static PomInfo read(Path pom) throws IOException {
        String text = Files.readString(pom, StandardCharsets.UTF_8);
        String packaging;
        try (Reader reader = Files.newBufferedReader(pom, StandardCharsets.UTF_8)) {
            Model model = new MavenXpp3Reader().read(reader, false);
            packaging = model.getPackaging();
        } catch (XmlPullParserException e) {
            throw new IOException("Unparseable POM " + pom + ": " + e.getMessage(), e);
        }
        if (packaging == null || packaging.isBlank()) {
            packaging = "jar";
        }
        return new PomInfo(packaging.trim(), text.contains(GRADLE_METADATA_MARKER));
    }

    /** Extension of the main artifact, or null when packaging is {@code pom} (no main artifact). */
    public String mainExtension() {
        if ("pom".equals(packaging)) {
            return null;
        }
        return PACKAGING_EXTENSIONS.getOrDefault(packaging, packaging);
    }
}
