package io.github.nhwalker.offlinecache.catalog;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A classifier/extension hint from {@code [metadata.offline-cache.hints]} (design doc §4.2).
 *
 * <p>Alias-keyed hints are converted to a {@code group:artifact} pattern when the catalog is read,
 * so matching only ever works on coordinates.
 *
 * @param key          the key as written in the catalog (alias or {@code group:artifact} glob)
 * @param groupGlob    glob for the groupId ({@code *} and {@code ?} wildcards)
 * @param artifactGlob glob for the artifactId
 * @param classifiers  classifiers to probe
 * @param extension    file extension to probe, default {@code jar}
 * @param source       catalog file the hint came from (for reporting)
 */
public record ClassifierHint(String key, String groupGlob, String artifactGlob,
                             List<String> classifiers, String extension, String source) {

    public static final String DEFAULT_EXTENSION = "jar";

    public ClassifierHint {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(groupGlob, "groupGlob");
        Objects.requireNonNull(artifactGlob, "artifactGlob");
        classifiers = List.copyOf(classifiers);
        extension = (extension == null || extension.isBlank()) ? DEFAULT_EXTENSION : extension;
        Objects.requireNonNull(source, "source");
    }

    public boolean matches(String groupId, String artifactId) {
        return globToRegex(groupGlob).matcher(groupId).matches()
                && globToRegex(artifactGlob).matcher(artifactId).matches();
    }

    /** Converts a glob with {@code *} / {@code ?} wildcards to an anchored regex; everything else is literal. */
    static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*' || c == '?') {
                if (!literal.isEmpty()) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(regex.toString());
    }
}
