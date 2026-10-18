package spike;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dockerjava.api.model.Device;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;

/**
 * Spike #5: does /dev/kvm passthrough (and the related group handling) work when Testcontainers
 * talks to Podman through its Docker-compatible API?
 *
 * <p>Each variant starts a one-shot container that opens /dev/kvm and issues KVM_GET_API_VERSION.
 * Outcomes are appended to {@code target/probe-results.txt}. Only the baseline variant asserts;
 * the others record what happened so the plan can state facts.
 */
class PodmanKvmProbeTest {

  static final String IMAGE = System.getProperty("probe.image", "localhost/vmtc-spike:media");
  static final Path RESULTS = Path.of("target", "probe-results.txt");

  static final String PROBE = String.join("\n",
      "import os, fcntl, json",
      "r = {}",
      "try:",
      "    fd = os.open('/dev/kvm', os.O_RDWR); r['kvm_api'] = fcntl.ioctl(fd, 0xAE00); r['open'] = 'ok'",
      "except Exception as e:",
      "    r['open'] = repr(e)",
      "try:",
      "    st = os.stat('/dev/kvm'); r['mode'] = oct(st.st_mode & 0o777); r['dev_gid'] = st.st_gid",
      "except Exception as e:",
      "    r['stat'] = repr(e)",
      "r['uid'] = os.getuid(); r['groups'] = os.getgroups(); r['pid1'] = open('/proc/1/comm').read().strip()",
      "print('PROBE ' + json.dumps(r), flush=True)");

  private static String run(String variant, Consumer<com.github.dockerjava.api.command.CreateContainerCmd> modifier)
      throws IOException {
    String logs;
    try (GenericContainer<?> c = new GenericContainer<>(IMAGE)
        .withCommand("python3", "-c", PROBE)
        .withCreateContainerCmdModifier(modifier)
        .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(60)))) {
      try {
        c.start();
        logs = c.getLogs();
      } catch (Exception e) {
        logs = "START FAILED: " + e + " / cause: " + (e.getCause() == null ? "" : e.getCause().toString());
      }
    }
    String line = logs.lines().filter(l -> l.startsWith("PROBE ")).findFirst().orElse(logs.strip());
    String rec = variant + " | " + line + System.lineSeparator();
    Files.createDirectories(RESULTS.getParent());
    Files.writeString(RESULTS, rec, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    System.out.print("### " + rec);
    return line;
  }

  @Test
  void engineInfo() throws IOException {
    var info = DockerClientFactory.instance().client().versionCmd().exec();
    String rec = "engine | " + info.getComponents() + " api=" + info.getApiVersion() + System.lineSeparator();
    Files.createDirectories(RESULTS.getParent());
    Files.writeString(RESULTS, rec, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    System.out.print("### " + rec);
  }

  /** Baseline: device mapping only. Expected to work when /dev/kvm is 0666 on the host. */
  @Test
  void deviceOnly() throws IOException {
    String out = run("device-only", cmd -> cmd.getHostConfig().withDevices(Device.parse("/dev/kvm")));
    if ("0666".equals(System.getenv("PROBE_EXPECT_KVM_MODE"))) {
      assertTrue(out.contains("\"open\": \"ok\""), out);
    }
  }

  /** GroupAdd keep-groups (crun) through the compat API. Relevant when /dev/kvm is 0660 root:kvm. */
  @Test
  void deviceKeepGroups() throws IOException {
    run("device+groupadd-keep-groups", cmd -> cmd.getHostConfig()
        .withDevices(Device.parse("/dev/kvm"))
        .withGroupAdd(List.of("keep-groups")));
  }

  /** crun annotation equivalent of keep-groups. */
  @Test
  void deviceKeepGroupsAnnotation() throws IOException {
    run("device+annotation-keep_original_groups", cmd -> cmd
        .withHostConfig(cmd.getHostConfig().withDevices(Device.parse("/dev/kvm"))
            .withAnnotations(Map.of("run.oci.keep_original_groups", "1"))));
  }

  /** Numeric/named kvm group via GroupAdd (works only if the group maps inside the user namespace). */
  @Test
  void deviceGroupAddKvm() throws IOException {
    run("device+groupadd-kvm", cmd -> cmd.getHostConfig()
        .withDevices(Device.parse("/dev/kvm"))
        .withGroupAdd(List.of(System.getenv().getOrDefault("PROBE_KVM_GID", "kvm"))));
  }

  /** HostConfig.Init=true through the compat API: what is PID 1? */
  @Test
  void initFlag() throws IOException {
    run("init=true", cmd -> cmd.getHostConfig().withInit(true));
  }

  /**
   * containerDisk extraction through the Docker-compatible API: create (not start) a container from a
   * FROM-scratch image holding /disk/disk.qcow2, stream it out with copyArchiveFromContainer, compare bytes.
   */
  @Test
  void containerDiskExtraction() throws Exception {
    String image = System.getProperty("probe.containerdisk", "localhost/vmtc-containerdisk-probe:latest");
    Path expected = Path.of(System.getProperty("probe.containerdisk.file", "containerdisk/disk.qcow2"));
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(expected), "no containerdisk probe file");
    var client = DockerClientFactory.instance().client();
    String digest = client.inspectImageCmd(image).exec().getId();
    String id = client.createContainerCmd(image).withCmd("/none").exec().getId();
    byte[] got = null;
    StringBuilder entries = new StringBuilder();
    // copy the DIRECTORY (the production design does not know the file name in advance)
    try (var tar = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
        client.copyArchiveFromContainerCmd(id, "/disk/").exec())) {
      org.apache.commons.compress.archivers.tar.TarArchiveEntry e;
      while ((e = tar.getNextEntry()) != null) {
        entries.append(e.getName()).append(e.isDirectory() ? "(dir)" : e.isFile() ? "(file)" : "(other)").append(' ');
        if (e.isFile()) {
          got = tar.readAllBytes();
        }
      }
      String rec = "containerdisk-dir | entries=" + entries.toString().trim() + " size=" + (got == null ? -1 : got.length)
          + " imageId=" + digest + " match=" + (got != null && java.util.Arrays.equals(got, Files.readAllBytes(expected)))
          + System.lineSeparator();
      Files.createDirectories(RESULTS.getParent());
      Files.writeString(RESULTS, rec, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
      System.out.print("### " + rec);
    } finally {
      client.removeContainerCmd(id).withForce(true).exec();
    }
    assertTrue(java.util.Arrays.equals(got, Files.readAllBytes(expected)));
  }

  /**
   * Image mount through the Docker-compatible API (docker-java MountType.IMAGE): the containerDisk image is mounted
   * read-only into the runner container; whole image and ImageOptions.subpath variants. No JVM-side download.
   */
  @Test
  void imageMount() throws Exception {
    String disk = System.getProperty("probe.containerdisk", "localhost/vmtc-containerdisk-probe:latest");
    Path expected = Path.of(System.getProperty("probe.containerdisk.file", "containerdisk/disk.qcow2"));
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(expected), "no containerdisk probe file");
    String want = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(expected)));
    for (String variant : new String[] {"whole", "subpath"}) {
      com.github.dockerjava.api.model.Mount m = new com.github.dockerjava.api.model.Mount()
          .withType(com.github.dockerjava.api.model.MountType.IMAGE).withSource(disk);
      // ReadOnly deliberately unset: Podman's compat API maps ReadOnly=true to an "ro" option that image mounts reject
      // ("fill out specgen: ro: invalid mount option"); image mounts are read-only by default (checked below).
      String file;
      if (variant.equals("whole")) {
        m.withTarget("/vm/image");
        file = "/vm/image/disk/disk.qcow2";
      } else {
        m.withTarget("/vm/base").withImageOptions(new com.github.dockerjava.api.model.ImageOptions().withSubpath("disk"));
        file = "/vm/base/disk.qcow2";
      }
      String out;
      try (GenericContainer<?> c = new GenericContainer<>(IMAGE)
          .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMounts(java.util.List.of(m)))
          .withCommand("sh", "-c", "sha256sum " + file + " | cut -c1-64; qemu-img info --output=json " + file
              + " | jq -r .format; (echo x > " + file + ".w) 2>&1 | grep -o 'Read-only file system'; grep ' /vm/' /proc/mounts | cut -d' ' -f1-4")
          .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(60)))) {
        try {
          c.start();
          out = c.getLogs().strip().replace("\n", " | ");
        } catch (Exception e) {
          StringBuilder chain = new StringBuilder("START FAILED:");
          for (Throwable x = e; x != null; x = x.getCause()) chain.append(" <- ").append(x.getClass().getSimpleName()).append(": ").append(x.getMessage());
          out = chain.toString().replace("\n", " ");
        }
      }
      String rec = "image-mount-" + variant + " | " + out + " | sha256 match=" + out.startsWith(want) + System.lineSeparator();
      Files.createDirectories(RESULTS.getParent());
      Files.writeString(RESULTS, rec, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
      System.out.print("### " + rec);
    }
  }

  /** Read-only bind mount of a file (the VM base-disk pattern). */
  @Test
  void readOnlyFileBind() throws IOException {
    Path f = Files.createTempFile("probe-disk", ".img");
    Files.writeString(f, "base-disk");
    String out;
    try (GenericContainer<?> c = new GenericContainer<>(IMAGE)
        .withFileSystemBind(f.toString(), "/vm/base/disk.img", BindMode.READ_ONLY)
        .withCommand("sh", "-c", "cat /vm/base/disk.img; echo; (echo x >> /vm/base/disk.img && echo WRITE_OK) 2>&1 | tail -1")
        .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(60)))) {
      c.start();
      out = c.getLogs().strip().replace('\n', ' ');
    }
    String rec = "ro-file-bind | " + out + System.lineSeparator();
    Files.writeString(RESULTS, rec, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    System.out.print("### " + rec);
    assertTrue(out.contains("base-disk") && !out.contains("WRITE_OK"), out);
  }
}
