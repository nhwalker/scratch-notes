package spike.ssh;

import com.github.dockerjava.api.model.Device;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spike R11: recorder SIDECAR. The VM runs on a Testcontainers Network with an alias; a second container (same media
 * image, tini + sleep infinity) records the VM's VNC over the container network with rfbsrc, then the file is copied out.
 * Measures: alias resolution, RFB banner over the network, recording duration vs wall time, stop latency incl. copy.
 * Properties: probe.image (media image), probe.disk, probe.accel, probe.spikeDir, probe.results, probe.label.
 */
class SidecarRecorderProbeTest {
    static final String PW = "vmtc";
    final List<String> lines = new ArrayList<>();

    void log(String s) { System.out.println("RESULT " + s); lines.add(s); }

    @Test
    void sidecar() throws Exception {
        String image = System.getProperty("probe.image");
        Path disk = Path.of(System.getProperty("probe.disk")).toAbsolutePath();
        String accel = System.getProperty("probe.accel", "kvm");
        Path spikeDir = Path.of(System.getProperty("probe.spikeDir")).toAbsolutePath();
        Path results = Path.of(System.getProperty("probe.results", "target/sidecar-probe.txt")).toAbsolutePath();
        Files.createDirectories(results.getParent());
        Duration ready = Duration.ofSeconds(accel.equals("kvm") ? 240 : 1500);
        String alias = "vm-" + Long.toHexString(System.nanoTime()).substring(0, 8);
        try (Network net = Network.newNetwork();
             GenericContainer<?> vm = new GenericContainer<>(DockerImageName.parse(image));
             GenericContainer<?> rec = new GenericContainer<>(DockerImageName.parse(image))) {
            log("label=" + System.getProperty("probe.label", "sidecar") + " accel=" + accel + " network=" + net.getId() + " alias=" + alias);
            vm.withEnv("VM_ACCEL", accel).withEnv("VM_SCREENS", "1").withEnv("VM_PORT_FORWARDS", "22:10022")
              .withEnv("QMP_BIND", "0.0.0.0").withEnv("VNC_BIND", "0.0.0.0")
              .withExposedPorts(10022, 4444, 5900)
              .withNetwork(net).withNetworkAliases(alias)
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
            log("vm started in " + ms(t0) + " ms");

            // recorder sidecar: same image, tini as PID 1, sleep infinity, spike scripts mounted
            rec.withNetwork(net)
               .withFileSystemBind(spikeDir.toString(), "/spike", BindMode.READ_ONLY)
               .withCreateContainerCmdModifier(c -> c.withEntrypoint("/usr/bin/tini", "--"))
               .withCommand("sleep", "infinity")
               .withStartupCheckStrategy(new org.testcontainers.containers.startupcheck.IsRunningStartupCheckStrategy());
            long t1 = System.nanoTime();
            rec.start();
            log("recorder started in " + ms(t1) + " ms; pid1=" + x(rec, "cat", "/proc/1/comm").trim());
            log("alias resolution: " + x(rec, "sh", "-c", "getent hosts " + alias + " || echo UNRESOLVED").trim());
            log("rfb banner over network: " + x(rec, "bash", "-c", "exec 3<>/dev/tcp/" + alias + "/5900 && head -c 12 <&3 || echo NO-CONNECT").trim());

            // wait for the guest (SSH root) so the console shows text, then start a tick loop on tty1
            SshProbe root = null;
            long deadline = System.nanoTime() + ready.toNanos();
            while (System.nanoTime() < deadline && root == null) {
                try { root = SshProbe.connect(vm.getHost(), vm.getMappedPort(10022), "root", PW, null, Duration.ofSeconds(10)); }
                catch (Exception e) { Thread.sleep(1000); }
            }
            assertTrue(root != null, "ssh not ready");
            try (SshProbe r = root) {
                r.exec("sh -c 'nohup sh -c \"for i in $(seq 1 20); do echo tick\\$i > /dev/tty1; sleep 1; done\" >/dev/null 2>&1 &'", Duration.ofSeconds(10));

                // record for 8 s
                x(rec, "mkdir", "-p", "/vm/out", "/vm/run");
                String start = x(rec, "bash", "-c", "VNC_HOST=" + alias + " /spike/rec.sh start sc 5900 10");
                log("rec start: " + start.trim());
                Thread.sleep(8000);
                long tStop = System.nanoTime();
                String stop = x(rec, "bash", "-c", "/spike/rec.sh stop sc");
                long tStopped = ms(tStop);
                String disc = x(rec, "bash", "-c", "gst-discoverer-1.0 /vm/out/sc.webm 2>&1 | grep -E 'Duration|Width|Height' | tr -s ' ' | tr '\\n' ' '");
                Path out = results.getParent().resolve("sidecar-" + accel + ".webm");
                rec.copyFileFromContainer("/vm/out/sc.webm", out.toString());
                long tTotal = ms(tStop);
                log("rec stop: " + stop.trim().replace('\n', '|'));
                log("stop latency: process gone after " + tStopped + " ms; validated + copied to host after " + tTotal + " ms; file " + Files.size(out) + " bytes; " + disc.trim());
                boolean ok = disc.contains("Duration: 0:00:0") && (disc.contains("Width: 1280") || disc.contains("Width: 640"));
                assertTrue(ok, "recording invalid: " + disc);
            }
            vm.copyFileFromContainer("/vm/out/serial.log", results.getParent().resolve("sidecar-serial-" + accel + ".log").toString());
        } finally {
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(results))) { lines.forEach(w::println); }
        }
    }

    static String x(GenericContainer<?> c, String... cmd) throws Exception {
        Container.ExecResult r = c.execInContainer(cmd);
        return r.getStdout() + (r.getStderr().isBlank() ? "" : " [stderr: " + r.getStderr().trim() + "]");
    }

    static long ms(long t0) { return (System.nanoTime() - t0) / 1_000_000; }
}
