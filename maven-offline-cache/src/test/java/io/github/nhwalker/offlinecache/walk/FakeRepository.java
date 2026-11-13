package io.github.nhwalker.offlinecache.walk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * In-process Maven repository for tests: serves files under a directory over HTTP (GET/HEAD,
 * 404 when absent). Files are written with a correct {@code .sha1} sidecar unless asked otherwise.
 */
final class FakeRepository implements AutoCloseable {

    enum Sha1 { CORRECT, NONE, WRONG }

    private final Path root;
    private final HttpServer server;

    FakeRepository(Path root) throws IOException {
        this.root = root;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    /** Writes a POM: {@code body} is inserted after the coordinates. */
    FakeRepository pom(String gav, String body) throws IOException {
        String[] p = gav.split(":");
        String xml = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>" + p[0] + "</groupId><artifactId>" + p[1] + "</artifactId><version>" + p[2] + "</version>\n"
                + body + "\n</project>\n";
        return file(gav, "", "pom", xml.getBytes(StandardCharsets.UTF_8), Sha1.CORRECT);
    }

    FakeRepository file(String gav, String classifier, String extension) throws IOException {
        return file(gav, classifier, extension, (gav + classifier + extension).getBytes(StandardCharsets.UTF_8), Sha1.CORRECT);
    }

    FakeRepository file(String gav, String classifier, String extension, String content) throws IOException {
        return file(gav, classifier, extension, content.getBytes(StandardCharsets.UTF_8), Sha1.CORRECT);
    }

    FakeRepository file(String gav, String classifier, String extension, byte[] content, Sha1 sha1) throws IOException {
        Path f = root.resolve(path(gav, classifier, extension));
        Files.createDirectories(f.getParent());
        Files.write(f, content);
        Path side = f.resolveSibling(f.getFileName() + ".sha1");
        switch (sha1) {
            case CORRECT -> Files.writeString(side, sha1(content));
            case WRONG -> Files.writeString(side, "0000000000000000000000000000000000000000");
            case NONE -> Files.deleteIfExists(side);
        }
        return this;
    }

    static String path(String gav, String classifier, String extension) {
        String[] p = gav.split(":");
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2]
                + (classifier.isEmpty() ? "" : "-" + classifier) + "." + extension;
    }

    private static String sha1(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String rel = ex.getRequestURI().getPath().replaceFirst("^/", "");
            Path f = root.resolve(rel).normalize();
            if (!f.startsWith(root) || !Files.isRegularFile(f)) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = Files.readAllBytes(f);
            if ("HEAD".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(200, -1);
                return;
            }
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
