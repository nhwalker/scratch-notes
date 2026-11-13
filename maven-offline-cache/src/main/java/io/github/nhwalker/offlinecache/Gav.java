package io.github.nhwalker.offlinecache;

import java.util.Objects;

/**
 * A Maven coordinate {@code groupId:artifactId:version} (no classifier / extension).
 */
public record Gav(String groupId, String artifactId, String version) implements Comparable<Gav> {

    public Gav {
        requireNonBlank(groupId, "groupId");
        requireNonBlank(artifactId, "artifactId");
        requireNonBlank(version, "version");
    }

    /** Parses {@code g:a:v}. */
    public static Gav parse(String coordinates) {
        String[] parts = coordinates.split(":", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Expected groupId:artifactId:version but got '" + coordinates + "'");
        }
        return new Gav(parts[0].trim(), parts[1].trim(), parts[2].trim());
    }

    /** {@code groupId:artifactId}. */
    public String module() {
        return groupId + ":" + artifactId;
    }

    @Override
    public int compareTo(Gav o) {
        int c = groupId.compareTo(o.groupId);
        if (c == 0) c = artifactId.compareTo(o.artifactId);
        if (c == 0) c = version.compareTo(o.version);
        return c;
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version;
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
