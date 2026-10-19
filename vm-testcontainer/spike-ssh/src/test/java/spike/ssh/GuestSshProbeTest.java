package spike.ssh;

import com.github.dockerjava.api.model.Device;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spikes A + C: a customised Rocky guest started through Testcontainers (rootless Podman compat API) with a slirp
 * hostfwd for port 22, driven over SSH (MINA SSHD) instead of the guest agent.
 *
 * System properties: probe.image, probe.disk, probe.accel (kvm|tcg), probe.keyDir (id_rsa, id_ecdsa, id_ed25519),
 * probe.spikeDir (container/spike), probe.results (output file), probe.label, probe.expectAgent (true|false),
 * probe.shadedJar (optional: run SshMatrix from the shaded jar against the forwarded port).
 */
class GuestSshProbeTest {
    static final String PW = "vmtc";
    final List<String> lines = new ArrayList<>();
    final Path outDir = Path.of(System.getProperty("probe.results", "target/ssh-probe.txt")).toAbsolutePath().getParent();

    void log(String s) {
        System.out.println("RESULT " + s);
        lines.add(s);
    }

    @Test
    void probe() throws Exception {
        String image = System.getProperty("probe.image");
        Path disk = Path.of(System.getProperty("probe.disk")).toAbsolutePath();
        String accel = System.getProperty("probe.accel", "kvm");
        Path keyDir = Path.of(System.getProperty("probe.keyDir")).toAbsolutePath();
        Path spikeDir = Path.of(System.getProperty("probe.spikeDir")).toAbsolutePath();
        boolean expectAgent = Boolean.parseBoolean(System.getProperty("probe.expectAgent", "true"));
        String label = System.getProperty("probe.label", "guest");
        Duration ready = Duration.ofSeconds(accel.equals("kvm") ? 240 : 1500);
        Duration t = Duration.ofSeconds(accel.equals("kvm") ? 60 : 300);
        Files.createDirectories(outDir);
        log("label=" + label + " accel=" + accel + " disk=" + disk.getFileName() + " expectAgent=" + expectAgent);
        log("security " + SshProbe.securityFacts());

        try (GenericContainer<?> vm = new GenericContainer<>(DockerImageName.parse(image))) {
            vm.withEnv("VM_ACCEL", accel).withEnv("VM_SCREENS", "2").withEnv("VM_PORT_FORWARDS", "22:10022")
              .withEnv("QMP_BIND", "0.0.0.0").withEnv("VNC_BIND", "0.0.0.0")
              .withExposedPorts(10022, 4444)
              .withFileSystemBind(disk.toString(), "/vm/base/disk.img", BindMode.READ_ONLY)
              .withFileSystemBind(spikeDir.toString(), "/spike", BindMode.READ_ONLY)
              .withCommand("bash", "-c", "exec /spike/start-vm.sh")
              .withCreateContainerCmdModifier(c -> {
                  c.getHostConfig().withInit(true);
                  if (accel.equals("kvm")) c.getHostConfig().withDevices(Device.parse("/dev/kvm:/dev/kvm:rwm"));
              })
              .waitingFor(Wait.forLogMessage(".*QEMU_STARTING.*\\n", 1).withStartupTimeout(Duration.ofSeconds(120)));
            long t0 = System.nanoTime();
            vm.start();
            String host = vm.getHost();
            int sshPort = vm.getMappedPort(10022);
            int qmpPort = vm.getMappedPort(4444);
            log("container started in " + ms(t0) + " ms; ssh forward " + host + ":" + sshPort + " (container 10022 -> guest 22); qmp " + qmpPort);

            // --- readiness: raw TCP connect vs SSH banner vs authenticated session (spike A) ---
            long tcpMs = -1, bannerMs = -1, authMs = -1;
            long deadline = System.nanoTime() + ready.toNanos();
            SshProbe root = null;
            while (System.nanoTime() < deadline) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(host, sshPort), 2000);
                    if (tcpMs < 0) tcpMs = ms(t0);
                    s.setSoTimeout(2000);
                    int b = s.getInputStream().read();
                    if (b >= 0 && bannerMs < 0) bannerMs = ms(t0);
                } catch (IOException ignored) { }
                if (bannerMs >= 0) {
                    try {
                        root = SshProbe.connect(host, sshPort, "root", PW, null, Duration.ofSeconds(20));
                        authMs = ms(t0);
                        break;
                    } catch (IOException e) {
                        log("auth attempt failed (retrying): " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }
                Thread.sleep(1000);
            }
            log("readiness: tcpConnectOk=" + tcpMs + "ms sshBanner=" + bannerMs + "ms rootPasswordAuth=" + authMs + "ms"
                    + " (slirp accepts the host-side connect before the guest listens; banner/auth are the real signals)");
            assertTrue(root != null, "no SSH session within " + ready);
            try (SshProbe r = root) {
                log("negotiated " + r.serverKeyAlgorithms());
                for (String cmd : List.of("id -un", "head -1 /etc/os-release", "uname -r", "cat /proc/cmdline",
                        "rpm -q qemu-guest-agent", "test -e /dev/virtio-ports/org.qemu.guest_agent.0 && echo agent-port-present || echo agent-port-absent",
                        "systemctl is-active qemu-guest-agent cloud-init 2>&1 | tr '\\n' ' '", "test -e /etc/cloud/cloud-init.disabled && echo cloud-init-disabled || echo cloud-init-enabled",
                        "ls /dev/fb*", "getenforce")) {
                    SshProbe.Exec e = r.exec(cmd, t);
                    log("root exec[" + cmd + "] rc=" + e.exitCode() + " out=" + e.stdout().trim().replace('\n', '|') + (e.stderr().isBlank() ? "" : " err=" + e.stderr().trim()) + " ms=" + e.millis());
                }
                int agentRc = r.exec("rpm -q qemu-guest-agent", t).exitCode();
                assertEquals(expectAgent, agentRc == 0, "qemu-guest-agent installed=" + (agentRc == 0) + " expected " + expectAgent);

                // --- SFTP throughput as root (spike A) ---
                Path blob = Files.createTempFile(outDir, "blob", ".bin");
                Path back = Files.createTempFile(outDir, "back", ".bin");
                byte[] buf = new byte[1_000_000];
                new java.util.Random(7).nextBytes(buf);
                try (var os = Files.newOutputStream(blob)) { for (int i = 0; i < 50; i++) os.write(buf); }
                double put = r.put(blob, "/tmp/blob50");
                String guestSha = r.exec("sha256sum /tmp/blob50", Duration.ofMinutes(5)).stdout().split("\\s+")[0];
                double get = r.get("/tmp/blob50", back);
                boolean shaOk = sha256(blob).equals(guestSha) && sha256(blob).equals(sha256(back));
                log(String.format("sftp[50MB] put=%.1fMB/s get=%.1fMB/s shaOk=%b", put, get, shaOk));
                assertTrue(shaOk, "sftp round trip checksum");
                Files.deleteIfExists(blob); Files.deleteIfExists(back);
            }

            // --- non-root user with keys + sudo (spike C) ---
            for (String kt : List.of("id_ed25519", "id_rsa", "id_ecdsa")) {
                try {
                    Collection<KeyPair> kp = SshProbe.loadKeys(keyDir.resolve(kt), null);
                    try (SshProbe v = SshProbe.connect(host, sshPort, "vmtc", null, kp, Duration.ofSeconds(20))) {
                        SshProbe.Exec e = v.exec("id -un", t);
                        log("vmtc auth[" + kt + "] OK user=" + e.stdout().trim());
                    }
                } catch (Exception e) {
                    log("vmtc auth[" + kt + "] FAIL " + e.getClass().getName() + ": " + e.getMessage());
                }
            }
            try (SshProbe v = SshProbe.connect(host, sshPort, "vmtc", PW, null, Duration.ofSeconds(20))) {
                SshProbe.Exec e = v.exec("sudo -n id -u", t);
                log("vmtc sudo rc=" + e.exitCode() + " out=" + e.stdout().trim() + " err=" + e.stderr().trim());
                assertEquals(0, e.exitCode(), "sudo -n as vmtc");
                assertEquals("0", e.stdout().trim());
                SshProbe.Exec env = v.exec("env FOO=bar sh -c 'echo $FOO'", t);
                log("vmtc exec env-prefix out=" + env.stdout().trim());

                // --- forceModeset over SSH + sudo, verified via QMP screendump of video1 (spike C) ---
                int[] before = screenSize(vm, host, qmpPort, "before");
                v.put(spikeDir.resolve("fb-activate.py"), "/tmp/fb-activate.py");
                SshProbe.Exec fb = v.exec("sudo -n python3 /tmp/fb-activate.py /dev/fb1", t);
                int[] after = before;
                long mdl = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                while (System.nanoTime() < mdl && after[0] == before[0] && after[1] == before[1]) {
                    Thread.sleep(200);
                    after = screenSize(vm, host, qmpPort, "after");
                }
                log("forceModeset via ssh+sudo rc=" + fb.exitCode() + " out=" + fb.stdout().trim() + " err=" + fb.stderr().trim()
                        + " video1 " + before[0] + "x" + before[1] + " -> " + after[0] + "x" + after[1]);
                assertEquals(0, fb.exitCode(), "fb-activate via sudo");
            }

            // --- wrong password ---
            try (SshProbe bad = SshProbe.connect(host, sshPort, "root", "nope", null, Duration.ofSeconds(20))) {
                log("wrong-password UNEXPECTED-OK");
            } catch (Exception e) {
                log("wrong-password rejected " + e.getClass().getName() + ": " + e.getMessage());
            }

            // --- shaded jar from a separate JVM against the same forwarded port (spike B) ---
            String jar = System.getProperty("probe.shadedJar", "");
            if (!jar.isBlank()) {
                Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar,
                        "--host", host, "--port", String.valueOf(sshPort), "--user", "root", "--password", PW,
                        "--key", keyDir.resolve("id_ed25519").toString(), "--key", keyDir.resolve("id_rsa").toString(), "--exec", "id -un")
                        .redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.waitFor();
                for (String l : out.split("\n")) if (l.startsWith("RESULT")) log("shaded-jar " + l.substring(7));
            }

            vm.copyFileFromContainer("/vm/out/serial.log", outDir.resolve("serial-" + label + "-" + accel + ".log").toString());
            Files.writeString(outDir.resolve("container-" + label + "-" + accel + ".log"), vm.getLogs());
        } finally {
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(System.getProperty("probe.results", "target/ssh-probe.txt"))))) {
                lines.forEach(w::println);
            }
        }
    }

    /** Minimal QMP: screendump video1 to /vm/out/<name>.png, copy it out, return PNG width/height. */
    static int[] screenSize(GenericContainer<?> vm, String host, int qmpPort, String name) throws Exception {
        try (Socket s = new Socket(host, qmpPort)) {
            s.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
            in.readLine();                                   // greeting
            out.println("{\"execute\":\"qmp_capabilities\"}");
            readReturn(in);
            out.println("{\"execute\":\"screendump\",\"arguments\":{\"filename\":\"/vm/out/" + name + ".png\",\"device\":\"video1\",\"format\":\"png\"}}");
            readReturn(in);
        }
        Path png = Files.createTempFile("shot", ".png");
        vm.copyFileFromContainer("/vm/out/" + name + ".png", png.toString());
        byte[] d = Files.readAllBytes(png);
        Files.delete(png);
        return new int[]{((d[16] & 255) << 24) | ((d[17] & 255) << 16) | ((d[18] & 255) << 8) | (d[19] & 255),
                ((d[20] & 255) << 24) | ((d[21] & 255) << 16) | ((d[22] & 255) << 8) | (d[23] & 255)};
    }

    static void readReturn(BufferedReader in) throws IOException {
        String l;
        while ((l = in.readLine()) != null) {
            if (l.contains("\"return\"")) return;
            if (l.contains("\"error\"")) throw new IOException("QMP error: " + l);
        }
        throw new IOException("QMP closed");
    }

    static long ms(long t0) { return (System.nanoTime() - t0) / 1_000_000; }

    static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(p)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
        return HexFormat.of().formatHex(md.digest());
    }
}
