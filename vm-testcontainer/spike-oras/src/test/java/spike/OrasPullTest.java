package spike;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Collectors;
import land.oras.ContainerRef;
import land.oras.Layer;
import land.oras.Manifest;
import land.oras.Registry;
import land.oras.auth.AuthStoreAuthenticationProvider;
import land.oras.auth.NoAuthProvider;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R13 spike, ORAS half: land.oras:oras-java-sdk against a local `registry:2` with htpasswd auth.
 * Artifacts are pushed by the official oras CLI in CI (see run-oras-ci.sh). Results: target/oras-results.txt.
 */
class OrasPullTest {

  static final Path RESULTS = Path.of("target", "oras-results.txt");
  static final String TITLE = "org.opencontainers.image.title";
  static final String TLS = System.getenv("ORAS_TLS");          // e.g. localhost:5443 (self-signed CA)
  static final String PLAIN = System.getenv("ORAS_PLAIN");      // e.g. localhost:5000 (http)
  static final String CA = System.getenv("ORAS_CA");
  static final String USER = System.getenv("ORAS_USER");
  static final String PASS = System.getenv("ORAS_PASS");
  static final Path EXPECT = System.getenv("ORAS_EXPECT_DIR") == null ? null : Path.of(System.getenv("ORAS_EXPECT_DIR"));
  static final String SCENARIO = System.getenv().getOrDefault("ORAS_SCENARIO", "default");

  static void record(String line) throws IOException {
    Files.createDirectories(RESULTS.getParent());
    Files.writeString(RESULTS, "[" + SCENARIO + "] " + line + System.lineSeparator(), StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    System.out.println("### [" + SCENARIO + "] " + line);
  }

  static String sha256(Path p) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
  }

  static Registry tlsWith(String user, String pass) {
    return Registry.builder().defaults(user, pass).withCaFile(CA).build();
  }

  static String describe(Manifest m) {
    return "digest=" + m.getDigest() + " artifactType=" + m.getArtifactType() + " layers="
        + m.getLayers().stream()
            .map(l -> l.getAnnotations().get(TITLE) + "|" + l.getMediaType() + "|" + l.getSize())
            .collect(Collectors.joining(", "));
  }

  static Layer byTitle(Manifest m, String title) {
    return m.getLayers().stream().filter(l -> title.equals(l.getAnnotations().get(TITLE))).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no layer titled '" + title + "'; available: "
            + m.getLayers().stream().map(l -> l.getAnnotations().get(TITLE)).toList()));
  }

  /** Download one layer by title and return the file. */
  static Path fetch(Registry r, String ref, String title, Path dir) {
    ContainerRef cref = ContainerRef.parse(ref);
    Manifest m = r.getManifest(cref);
    Layer l = byTitle(m, title);
    Path out = dir.resolve(title);
    r.fetchBlob(cref.withDigest(l.getDigest()), out);          // SDK verifies the blob digest
    return out;
  }

  @Test
  void anonymousRejected() throws Exception {
    assumeTrue(TLS != null && "default".equals(SCENARIO));
    Registry anon = Registry.builder().withCaFile(CA).withAuthProvider(new NoAuthProvider()).build();
    var e = assertThrows(RuntimeException.class, () -> anon.getManifest(ContainerRef.parse(TLS + "/vmtc/single:v1")));
    record("anonymous -> " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).replace('\n', ' '));
  }

  @Test
  void wrongPasswordRejected() throws Exception {
    assumeTrue(TLS != null && "default".equals(SCENARIO));
    var e = assertThrows(RuntimeException.class,
        () -> tlsWith(USER, "wrong").getManifest(ContainerRef.parse(TLS + "/vmtc/single:v1")));
    record("wrong password -> " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).replace('\n', ' '));
  }

  @Test
  void explicitCredentialsTls(@TempDir Path t) throws Exception {
    assumeTrue(TLS != null && "default".equals(SCENARIO));
    Registry r = tlsWith(USER, PASS);
    Manifest m = r.getManifest(ContainerRef.parse(TLS + "/vmtc/single:v1"));
    record("manifest single:v1 " + describe(m));
    Path out = fetch(r, TLS + "/vmtc/single:v1", "disk.qcow2", t);
    assertEquals(sha256(EXPECT.resolve("disk.qcow2")), sha256(out));
    record("explicit creds over TLS (custom CA) -> disk.qcow2 sha256 matches (" + Files.size(out) + " bytes)");
  }

  @Test
  void multiFileSelectByTitle(@TempDir Path t) throws Exception {
    assumeTrue(TLS != null && "default".equals(SCENARIO));
    Registry r = tlsWith(USER, PASS);
    record("manifest multi:v1 " + describe(r.getManifest(ContainerRef.parse(TLS + "/vmtc/multi:v1"))));
    assertEquals(sha256(EXPECT.resolve("disk.qcow2")), sha256(fetch(r, TLS + "/vmtc/multi:v1", "disk.qcow2", t)));
    assertEquals(sha256(EXPECT.resolve("README.txt")), sha256(fetch(r, TLS + "/vmtc/multi:v1", "README.txt", t)));
    var e = assertThrows(IllegalArgumentException.class, () -> fetch(r, TLS + "/vmtc/multi:v1", "nope.img", t));
    record("multi-file: selected both layers by title; missing title -> " + e.getMessage());
  }

  @Test
  void compressedLayer(@TempDir Path t) throws Exception {
    assumeTrue(TLS != null && "default".equals(SCENARIO));
    Registry r = tlsWith(USER, PASS);
    record("manifest xz:v1 " + describe(r.getManifest(ContainerRef.parse(TLS + "/vmtc/xz:v1"))));
    Path xz = fetch(r, TLS + "/vmtc/xz:v1", "disk.qcow2.xz", t);
    Path out = t.resolve("disk.qcow2");
    try (InputStream in = new XZCompressorInputStream(Files.newInputStream(xz))) {
      Files.copy(in, out);
    }
    assertEquals(sha256(EXPECT.resolve("disk.qcow2")), sha256(out));
    record("compressed layer disk.qcow2.xz -> decompressed sha256 matches original");
  }

  @Test
  void tagMoveAndDigestPinning(@TempDir Path t) throws Exception {
    assumeTrue(TLS != null && "default".equals(SCENARIO));
    Registry r = tlsWith(USER, PASS);
    String oldDigest = System.getenv("ORAS_MOVING_OLD");
    String newDigest = System.getenv("ORAS_MOVING_NEW");
    String now = r.getManifest(ContainerRef.parse(TLS + "/vmtc/moving:latest")).getDigest();
    assertEquals(newDigest, now);
    assertNotEquals(oldDigest, now);
    Manifest pinned = r.getManifest(ContainerRef.parse(TLS + "/vmtc/moving@" + oldDigest));
    assertEquals(oldDigest, pinned.getDigest());
    record("tag move: moving:latest resolves to new digest " + now.substring(0, 19)
        + "…; old digest still fetchable via @digest (" + oldDigest.substring(0, 19) + "…)");
  }

  @Test
  void plainHttpRegistry(@TempDir Path t) throws Exception {
    assumeTrue(PLAIN != null && "default".equals(SCENARIO));
    Registry r = Registry.builder().insecure(PLAIN, USER, PASS).build();
    Path out = fetch(r, PLAIN + "/vmtc/single:v1", "disk.qcow2", t);
    assertEquals(sha256(EXPECT.resolve("disk.qcow2")), sha256(out));
    record("plain-http registry (insecure builder) with creds -> ok");
  }

  /** ORAS_SCENARIO=authfile: REGISTRY_AUTH_FILE points at a docker-style config for the TLS registry. */
  @Test
  void authFileFromEnv(@TempDir Path t) throws Exception {
    assumeTrue(TLS != null && "authfile".equals(SCENARIO));
    Registry r = Registry.builder().withAuthProvider(new AuthStoreAuthenticationProvider()).withCaFile(CA).build();
    Path out = fetch(r, TLS + "/vmtc/single:v1", "disk.qcow2", t);
    assertArrayEquals(Files.readAllBytes(EXPECT.resolve("disk.qcow2")), Files.readAllBytes(out));
    record("REGISTRY_AUTH_FILE=" + System.getenv("REGISTRY_AUTH_FILE") + " -> credentials picked up, pull ok");
  }

  /** ORAS_SCENARIO=podman: `podman login` wrote $XDG_RUNTIME_DIR/containers/auth.json for the plain registry. */
  @Test
  void podmanLoginAuthJson(@TempDir Path t) throws Exception {
    assumeTrue(PLAIN != null && "podman".equals(SCENARIO));
    Registry r = Registry.builder().insecure(PLAIN).withAuthProvider(new AuthStoreAuthenticationProvider()).build();
    Path out = fetch(r, PLAIN + "/vmtc/single:v1", "disk.qcow2", t);
    assertArrayEquals(Files.readAllBytes(EXPECT.resolve("disk.qcow2")), Files.readAllBytes(out));
    record("podman login auth.json ($XDG_RUNTIME_DIR=" + System.getenv("XDG_RUNTIME_DIR") + ") -> credentials picked up, pull ok");
  }
}
