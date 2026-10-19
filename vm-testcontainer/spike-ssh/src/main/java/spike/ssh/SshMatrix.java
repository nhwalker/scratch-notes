package spike.ssh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Spike B: authentication matrix against a REAL OpenSSH server (not MINA's own server), run both from the plain
 * classpath and from the shaded/relocated jar.
 *
 * Usage: SshMatrix --host H --port P --user U [--password PW] [--key /path/id_rsa ...] [--sudo] [--sftp-mb 50] [--exec CMD]
 * Prints one "RESULT key=value" line per probe; never throws (failures are reported as values).
 */
public final class SshMatrix {
    public static void main(String[] argv) throws Exception {
        Map<String, List<String>> a = parse(argv);
        String host = a.getOrDefault("host", List.of("127.0.0.1")).get(0);
        int port = Integer.parseInt(a.getOrDefault("port", List.of("22")).get(0));
        String user = a.getOrDefault("user", List.of("root")).get(0);
        String password = a.containsKey("password") ? a.get("password").get(0) : null;
        Duration t = Duration.ofSeconds(30);

        System.out.println("RESULT security " + SshProbe.securityFacts());

        if (password != null) {
            try (SshProbe p = SshProbe.connect(host, port, user, password, null, t)) {
                SshProbe.Exec e = p.exec("id -un", t);
                System.out.println("RESULT auth[password] OK user=" + e.stdout().trim() + " negotiated=" + p.serverKeyAlgorithms());
            } catch (Exception e) {
                System.out.println("RESULT auth[password] FAIL " + e.getClass().getName() + ": " + e.getMessage());
            }
            try (SshProbe p = SshProbe.connect(host, port, user, password + "-WRONG", null, t)) {
                System.out.println("RESULT auth[wrong-password] UNEXPECTED-OK");
            } catch (Exception e) {
                System.out.println("RESULT auth[wrong-password] rejected " + e.getClass().getName() + ": " + e.getMessage());
            }
        }
        for (String key : a.getOrDefault("key", List.of())) {
            String label = Path.of(key).getFileName().toString();
            try {
                Collection<KeyPair> kp = SshProbe.loadKeys(Path.of(key), null);
                String algo = kp.isEmpty() ? "none" : kp.iterator().next().getPrivate().getAlgorithm();
                try (SshProbe p = SshProbe.connect(host, port, user, null, kp, t)) {
                    SshProbe.Exec e = p.exec("id -un", t);
                    System.out.println("RESULT auth[key:" + label + "] OK algo=" + algo + " user=" + e.stdout().trim());
                }
            } catch (Exception e) {
                System.out.println("RESULT auth[key:" + label + "] FAIL " + e.getClass().getName() + ": " + e.getMessage());
            }
        }
        if (password != null) {
            try (SshProbe p = SshProbe.connect(host, port, user, password, null, t)) {
                for (String cmd : a.getOrDefault("exec", List.of("uname -a", "exit 3", "echo err >&2"))) {
                    SshProbe.Exec e = p.exec(cmd, t);
                    System.out.println("RESULT exec[" + cmd + "] rc=" + e.exitCode() + " out=" + e.stdout().trim().replace('\n', '|')
                            + " err=" + e.stderr().trim() + " ms=" + e.millis());
                }
                if (a.containsKey("signal-tests")) {
                    for (String cmd : List.of("timeout -s KILL 2 sleep 61", "timeout 2 sleep 61", "timeout --preserve-status -s KILL 2 sleep 61",
                            "timeout --foreground -s KILL 2 sleep 61", "timeout --foreground -k 1 2 sleep 61",
                            "timeout --foreground -k 1 2 sh -c 'trap \"\" TERM; sleep 61'", "timeout -k 1 2 sh -c 'trap \"\" TERM; sleep 61'",
                            "timeout --foreground -s KILL 2 sh -c 'sleep 62 & sleep 61'",
                            "sh -c 'kill -9 $$'", "sh -c 'kill -TERM $$'", "sh -c 'exit 137'")) {
                        SshProbe.Exec e = p.exec(cmd, t);
                        System.out.println("RESULT signal[" + cmd + "] rc=" + e.exitCode() + " signal=" + e.exitSignal() + " ms=" + e.millis() + " err=" + e.stderr().trim());
                    }
                    System.out.println("RESULT leftover-after-timeout " + p.exec("sleep 3; pgrep -fa 'sleep 6[12]' || echo none", t).stdout().trim().replace('\n', '|'));
                    System.out.println("RESULT channel-close " + p.execThenClose("sleep 300", Duration.ofSeconds(1)));
                    Thread.sleep(1000);
                    System.out.println("RESULT leftover-after-close " + p.exec("pgrep -fa 'sleep 300' || echo none", t).stdout().trim().replace('\n', '|'));
                    System.out.println("RESULT channel-close-pty-less-nohup " + p.execThenClose("sh -c 'sleep 301 & wait'", Duration.ofSeconds(1)));
                    Thread.sleep(1000);
                    System.out.println("RESULT leftover-after-close2 " + p.exec("pgrep -fa 'sleep 30[01]' || echo none", t).stdout().trim().replace('\n', '|'));
                    p.exec("pkill -f 'sleep 30[01]' || true", t);
                }
                if (a.containsKey("sudo")) {
                    SshProbe.Exec e = p.exec("sudo -n id -u", t);
                    System.out.println("RESULT sudo rc=" + e.exitCode() + " out=" + e.stdout().trim() + " err=" + e.stderr().trim());
                }
                int mb = Integer.parseInt(a.getOrDefault("sftp-mb", List.of("0")).get(0));
                if (mb > 0) {
                    Path tmp = Files.createTempFile("blob", ".bin");
                    Path back = Files.createTempFile("back", ".bin");
                    byte[] buf = new byte[1_000_000];
                    new java.util.Random(42).nextBytes(buf);
                    try (var os = Files.newOutputStream(tmp)) { for (int i = 0; i < mb; i++) os.write(buf); }
                    double putRate = p.put(tmp, "/tmp/blob-" + mb);
                    String guestSha = p.exec("sha256sum /tmp/blob-" + mb, Duration.ofMinutes(5)).stdout().split("\\s+")[0];
                    double getRate = p.get("/tmp/blob-" + mb, back);
                    String want = sha256(tmp), got = sha256(back);
                    System.out.printf("RESULT sftp[%dMB] put=%.1fMB/s get=%.1fMB/s shaOk=%b%n", mb, putRate, getRate,
                            want.equals(guestSha) && want.equals(got));
                    Files.deleteIfExists(tmp); Files.deleteIfExists(back);
                }
            } catch (Exception e) {
                System.out.println("RESULT exec-session FAIL " + e.getClass().getName() + ": " + e.getMessage()); e.printStackTrace(System.out);
            }
        }
    }

    static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(p)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
        return HexFormat.of().formatHex(md.digest());
    }

    static Map<String, List<String>> parse(String[] argv) {
        Map<String, List<String>> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < argv.length; i++) {
            if (!argv[i].startsWith("--")) throw new IllegalArgumentException(argv[i]);
            String k = argv[i].substring(2);
            boolean flag = k.equals("sudo") || k.equals("signal-tests");
            String v = flag ? "true" : argv[++i];
            m.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return m;
    }
}
