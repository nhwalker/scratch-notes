package io.github.nhwalker.offlinecache.mirror;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checksum generation for output files (design doc §7): md5, sha1, sha256, sha512 sidecars.
 */
public final class Checksums {

    /** Sidecar extension → JCA algorithm name. */
    public static final Map<String, String> ALGORITHMS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("md5", "MD5");
        m.put("sha1", "SHA-1");
        m.put("sha256", "SHA-256");
        m.put("sha512", "SHA-512");
        ALGORITHMS = Map.copyOf(m);
    }

    private static final List<String> ORDER = List.of("md5", "sha1", "sha256", "sha512");

    private Checksums() {
    }

    /** Computes all four digests of a file in one pass; keys are sidecar extensions. */
    public static Map<String, String> compute(Path file) throws IOException {
        Map<String, MessageDigest> digests = new LinkedHashMap<>();
        for (String ext : ORDER) {
            try {
                digests.put(ext, MessageDigest.getInstance(ALGORITHMS.get(ext)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("JDK lacks " + ALGORITHMS.get(ext), e);
            }
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                for (MessageDigest d : digests.values()) {
                    d.update(buffer, 0, n);
                }
            }
        }
        Map<String, String> result = new LinkedHashMap<>();
        digests.forEach((ext, d) -> result.put(ext, HexFormat.of().formatHex(d.digest())));
        return result;
    }

    /**
     * Writes any missing {@code <file>.<algo>} sidecars.
     *
     * @return sidecar extensions that were generated (empty if all already existed)
     */
    public static List<String> writeMissing(Path file) throws IOException {
        List<String> missing = ORDER.stream()
                .filter(ext -> !Files.exists(sidecar(file, ext)))
                .toList();
        if (missing.isEmpty()) {
            return missing;
        }
        Map<String, String> values = compute(file);
        for (String ext : missing) {
            Files.writeString(sidecar(file, ext), values.get(ext), StandardCharsets.US_ASCII);
        }
        return missing;
    }

    /**
     * Reads the hex digest from a sidecar file. Upstream files sometimes contain
     * {@code "<hex>  <filename>"}; only the first token is used.
     */
    public static String readSidecar(Path sidecar) throws IOException {
        String content = Files.readString(sidecar, StandardCharsets.US_ASCII).trim();
        int space = content.indexOf(' ');
        return (space < 0 ? content : content.substring(0, space)).toLowerCase();
    }

    public static Path sidecar(Path file, String ext) {
        return file.resolveSibling(file.getFileName() + "." + ext);
    }
}
