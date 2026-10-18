package spike;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R13 spike, HTTP half. Two in-process servers on different ports (= different authorities):
 * server A requires credentials and serves the image (plain, .gz, .xz, .zst) plus redirects;
 * server B serves the same file without auth and records whether an Authorization header arrived.
 * {@link Downloader} is the candidate production algorithm; results go to target/http-results.txt.
 */
class HttpDownloadTest {

  static final Path RESULTS = Path.of("target", "http-results.txt");
  static HttpServer a, b;
  static byte[] image;
  static final Map<String, byte[]> files = new ConcurrentHashMap<>();
  static final Map<String, String> lastAuthSeenByB = new ConcurrentHashMap<>();

  static void record(String line) throws IOException {
    Files.createDirectories(RESULTS.getParent());
    Files.writeString(RESULTS, line + System.lineSeparator(), StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    System.out.println("### " + line);
  }

  @BeforeAll
  static void servers() throws Exception {
    image = new byte[3 * 1024 * 1024];
    new Random(42).nextBytes(image);
    for (int i = 0; i < image.length; i += 7) image[i] = 0;   // make it a bit compressible
    files.put("/disk.qcow2", image);
    files.put("/disk.qcow2.gz", compress("gz", image));
    files.put("/disk.qcow2.xz", compress("xz", image));
    files.put("/disk.qcow2.zst", compress("zst", image));

    b = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    b.createContext("/", ex -> {
      lastAuthSeenByB.put(ex.getRequestURI().getPath(), String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
      serve(ex, files.get(ex.getRequestURI().getPath()));
    });
    b.start();

    a = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    a.createContext("/", ex -> {
      String path = ex.getRequestURI().getPath();
      String auth = ex.getRequestHeaders().getFirst("Authorization");
      boolean ok = ("Basic " + Base64.getEncoder().encodeToString("vmtc:s3cret".getBytes())).equals(auth)
          || "Bearer tok-123".equals(auth);
      if (!ok) {
        ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"spike\"");
        ex.sendResponseHeaders(401, -1);
        ex.close();
        return;
      }
      if (path.equals("/redirect-cross")) {       // to another authority (like a pre-signed CDN URL)
        redirect(ex, "http://127.0.0.1:" + b.getAddress().getPort() + "/disk.qcow2");
      } else if (path.equals("/redirect-same")) {
        redirect(ex, "/disk.qcow2");
      } else {
        serve(ex, files.get(path));
      }
    });
    a.start();
  }

  @AfterAll
  static void stop() {
    a.stop(0);
    b.stop(0);
  }

  static void redirect(HttpExchange ex, String location) throws IOException {
    ex.getResponseHeaders().add("Location", location);
    ex.sendResponseHeaders(302, -1);
    ex.close();
  }

  static void serve(HttpExchange ex, byte[] body) throws IOException {
    if (body == null) {
      ex.sendResponseHeaders(404, -1);
      ex.close();
      return;
    }
    ex.sendResponseHeaders(200, body.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(body);
    }
  }

  static byte[] compress(String kind, byte[] data) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (OutputStream os = switch (kind) {
      case "gz" -> new GZIPOutputStream(bos);
      case "xz" -> new XZCompressorOutputStream(bos);
      case "zst" -> new ZstdCompressorOutputStream(bos);
      default -> throw new IllegalArgumentException(kind);
    }) {
      os.write(data);
    }
    return bos.toByteArray();
  }

  static String sha256(byte[] d) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(d));
  }

  String urlA(String path) {
    return "http://127.0.0.1:" + a.getAddress().getPort() + path;
  }

  /** Candidate production algorithm (PLAN §9.3 HTTP flow). */
  static final class Downloader {
    final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    /** Returns the sha256 of the bytes as downloaded (before decompression); writes decompressed output to dest. */
    String download(URI url, String authHeader, String expectedSha256, Path dest) throws Exception {
      URI current = url;
      String origin = url.getScheme() + "://" + url.getAuthority();
      for (int hop = 0; hop <= 5; hop++) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(current).GET();
        String curOrigin = current.getScheme() + "://" + current.getAuthority();
        if (authHeader != null && curOrigin.equals(origin)) {       // credentials only to the original origin
          rb.header("Authorization", authHeader);
        }
        HttpResponse<InputStream> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
        int sc = resp.statusCode();
        if (sc >= 300 && sc < 400) {
          resp.body().close();
          current = current.resolve(resp.headers().firstValue("Location").orElseThrow());
          continue;
        }
        if (sc == 401 || sc == 403) {
          resp.body().close();
          throw new SecurityException("HTTP " + sc + " for " + url + " (credentials missing or rejected)");
        }
        if (sc != 200) {
          resp.body().close();
          throw new IOException("HTTP " + sc + " for " + url);
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        Path tmp = Files.createTempFile(dest.getParent(), "dl", ".tmp");
        try (InputStream raw = new DigestInputStream(resp.body(), md);
             InputStream in = decompressor(url.getPath(), raw)) {
          Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        String got = HexFormat.of().formatHex(md.digest());
        if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(got)) {
          Files.delete(tmp);
          throw new IllegalStateException("sha256 mismatch: expected " + expectedSha256 + " got " + got);
        }
        Files.move(tmp, dest, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return got;
      }
      throw new IOException("too many redirects");
    }

    static InputStream decompressor(String path, InputStream raw) throws IOException {
      if (path.endsWith(".gz")) return new GZIPInputStream(raw, 1 << 16);
      if (path.endsWith(".xz")) return new XZCompressorInputStream(raw);
      if (path.endsWith(".zst")) return new ZstdCompressorInputStream(raw);
      return raw;
    }
  }

  static String basic() {
    return "Basic " + Base64.getEncoder().encodeToString("vmtc:s3cret".getBytes());
  }

  @Test
  void noCredentials401(@TempDir Path t) throws Exception {
    var e = assertThrows(SecurityException.class,
        () -> new Downloader().download(URI.create(urlA("/disk.qcow2")), null, null, t.resolve("x")));
    record("http no-credentials -> " + e.getMessage());
  }

  @Test
  void basicAuthAndChecksum(@TempDir Path t) throws Exception {
    String got = new Downloader().download(URI.create(urlA("/disk.qcow2")), basic(), sha256(image), t.resolve("x"));
    assertArrayEquals(image, Files.readAllBytes(t.resolve("x")));
    record("http basic+sha256 -> ok sha=" + got.substring(0, 12));
  }

  @Test
  void bearerAuth(@TempDir Path t) throws Exception {
    new Downloader().download(URI.create(urlA("/disk.qcow2")), "Bearer tok-123", null, t.resolve("x"));
    assertArrayEquals(image, Files.readAllBytes(t.resolve("x")));
    record("http bearer -> ok");
  }

  @Test
  void checksumMismatchRejected(@TempDir Path t) throws Exception {
    var e = assertThrows(IllegalStateException.class,
        () -> new Downloader().download(URI.create(urlA("/disk.qcow2")), basic(), "00".repeat(32), t.resolve("x")));
    assertFalse(Files.exists(t.resolve("x")));
    record("http sha256 mismatch -> rejected, no file left: " + e.getMessage().substring(0, 40));
  }

  @Test
  void decompressByExtension(@TempDir Path t) throws Exception {
    for (String ext : new String[] {"gz", "xz", "zst"}) {
      Path out = t.resolve("x-" + ext);
      String shaCompressed = new Downloader().download(URI.create(urlA("/disk.qcow2." + ext)), basic(),
          sha256(files.get("/disk.qcow2." + ext)), out);
      assertArrayEquals(image, Files.readAllBytes(out));
      record("http ." + ext + " -> decompressed matches original; checksum verified on compressed bytes ("
          + files.get("/disk.qcow2." + ext).length + " -> " + image.length + " bytes) sha=" + shaCompressed.substring(0, 12));
    }
  }

  @Test
  void crossOriginRedirectDropsAuth(@TempDir Path t) throws Exception {
    lastAuthSeenByB.clear();
    new Downloader().download(URI.create(urlA("/redirect-cross")), basic(), sha256(image), t.resolve("x"));
    assertEquals("null", lastAuthSeenByB.get("/disk.qcow2"));
    new Downloader().download(URI.create(urlA("/redirect-same")), basic(), sha256(image), t.resolve("y"));
    record("http redirect: cross-origin target saw Authorization=" + lastAuthSeenByB.get("/disk.qcow2")
        + "; same-origin redirect succeeded with auth");
  }

  /** Fact-finding: what does the JDK do on its own with a user-set Authorization header and Redirect.NORMAL? */
  @Test
  void jdkRedirectNormalBehaviour() throws Exception {
    lastAuthSeenByB.clear();
    HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    var r = c.send(HttpRequest.newBuilder(URI.create(urlA("/redirect-cross"))).header("Authorization", basic()).build(),
        HttpResponse.BodyHandlers.ofByteArray());
    record("jdk Redirect.NORMAL cross-origin: status=" + r.statusCode() + " target saw Authorization="
        + ("null".equals(lastAuthSeenByB.get("/disk.qcow2")) ? "no" : "YES (leaked)"));
    assertNotNull(r);
  }

  @Test
  void jackson2AndSdkJackson3Coexist() throws Exception {
    var m2 = new com.fasterxml.jackson.databind.ObjectMapper();
    var n2 = m2.readTree("{\"a\":1}");
    var ref = land.oras.ContainerRef.parse("localhost:5000/vmtc/single:v1");   // loads SDK + Jackson 3 classes
    var m3 = tools.jackson.databind.json.JsonMapper.builder().build();
    var n3 = m3.readTree("{\"b\":2}");
    assertTrue(n2.get("a").asInt() == 1 && n3.get("b").asInt() == 2);
    record("jackson2 " + com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION + " + jackson3 (SDK) coexist; ContainerRef="
        + ref.getRegistry() + "/" + ref.getRepository() + ":" + ref.getTag());
  }
}
