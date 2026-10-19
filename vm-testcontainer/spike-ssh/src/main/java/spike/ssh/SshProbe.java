package spike.ssh;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

/** Thin wrapper over MINA SSHD used by both the local matrix (spike B) and the guest probe (spikes A/C). */
public final class SshProbe implements AutoCloseable {
    public record Exec(int exitCode, String stdout, String stderr, long millis) {}

    private final SshClient client;
    private final ClientSession session;

    private SshProbe(SshClient client, ClientSession session) {
        this.client = client;
        this.session = session;
    }

    /** What the library sees on this JVM/classpath (spike B evidence). */
    public static String securityFacts() {
        return "eddsaSupported=" + SecurityUtils.isEDDSACurveSupported()
                + " bouncyCastleRegistered=" + SecurityUtils.isBouncyCastleRegistered()
                + " ecc=" + SecurityUtils.isECCSupported()
                + " java=" + System.getProperty("java.version")
                + " sshdPackage=" + SshClient.class.getPackageName();
    }

    public static Collection<KeyPair> loadKeys(Path privateKey, String passphrase) throws IOException, java.security.GeneralSecurityException {
        try (InputStream in = Files.newInputStream(privateKey)) {
            Iterable<KeyPair> it = SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName(privateKey.toString()), in,
                    passphrase == null ? FilePasswordProvider.EMPTY : FilePasswordProvider.of(passphrase));
            java.util.ArrayList<KeyPair> list = new java.util.ArrayList<>();
            it.forEach(list::add);
            return list;
        }
    }

    /** Connect + authenticate. Exactly one of password / keys is used. Host keys are accepted blindly (spike; D8 spirit). */
    public static SshProbe connect(String host, int port, String user, String password, Collection<KeyPair> keys, Duration timeout)
            throws IOException {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((s, addr, key) -> true);
        client.start();
        try {
            ClientSession session = client.connect(user, host, port).verify(timeout).getSession();
            if (password != null) {
                session.addPasswordIdentity(password);
            }
            if (keys != null) {
                session.setKeyIdentityProvider(KeyIdentityProvider.wrapKeyPairs(keys));
            }
            session.auth().verify(timeout);
            return new SshProbe(client, session);
        } catch (IOException | RuntimeException e) {
            client.stop();
            throw e;
        }
    }

    public Exec exec(String command, Duration timeout) throws IOException {
        long t0 = System.nanoTime();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (ClientChannel ch = session.createExecChannel(command)) {
            ch.setOut(out);
            ch.setErr(err);
            ch.open().verify(timeout);
            ch.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeout);
            Integer rc = ch.getExitStatus();
            return new Exec(rc == null ? -1 : rc, out.toString(), err.toString(), (System.nanoTime() - t0) / 1_000_000);
        }
    }

    /** SFTP put via the synchronous handle API (open/write/close); returns MB/s. */
    public double put(Path local, String remote) throws IOException {
        long size = Files.size(local);
        long t0 = System.nanoTime();
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
             InputStream in = Files.newInputStream(local);
             SftpClient.CloseableHandle h = sftp.open(remote, SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate)) {
            byte[] buf = new byte[256 * 1024];
            long off = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                sftp.write(h, off, buf, 0, n);
                off += n;
            }
        }
        return size / 1e6 / ((System.nanoTime() - t0) / 1e9);
    }

    /** SFTP get; returns MB/s. */
    public double get(String remote, Path local) throws IOException {
        long t0 = System.nanoTime();
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
             InputStream in = sftp.read(remote, 256 * 1024);
             OutputStream os = Files.newOutputStream(local)) {
            in.transferTo(os);
        }
        return Files.size(local) / 1e6 / ((System.nanoTime() - t0) / 1e9);
    }

    public List<String> serverKeyAlgorithms() {
        return List.of(String.valueOf(session.getNegotiatedKexParameter(org.apache.sshd.common.kex.KexProposalOption.SERVERKEYS)),
                String.valueOf(session.getNegotiatedKexParameter(org.apache.sshd.common.kex.KexProposalOption.ALGORITHMS)),
                String.valueOf(session.getNegotiatedKexParameter(org.apache.sshd.common.kex.KexProposalOption.S2CENC)));
    }

    @Override
    public void close() throws IOException {
        session.close();
        client.stop();
    }
}
