package io.github.nhwalker.offlinecache.walk;

import io.github.nhwalker.offlinecache.RunConfig;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.supplier.SessionBuilderSupplier;
import org.eclipse.aether.transfer.TransferListener;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.repository.JreProxySelector;
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates the Maven Resolver objects for a run (design doc §3, §6).
 *
 * <p>The output directory is used as Resolver's <em>local repository</em>, so everything Resolver
 * downloads (including parent POMs and imported BOMs read during model building) lands there in
 * Maven layout. Resolver's bookkeeping files are removed by the cleanup step afterwards.
 */
public final class ResolverSessionFactory {

    public static final String CENTRAL_ID = "nexus-central";
    public static final String PLUGINS_ID = "nexus-plugins";

    private ResolverSessionFactory() {
    }

    public static RepositorySystem newRepositorySystem() {
        return new RepositorySystemSupplier().get();
    }

    /** Caller must close the session. Minimal session, used by tests. */
    public static RepositorySystemSession.CloseableSession newSession(RepositorySystem system, Path localRepo) {
        return new SessionBuilderSupplier(system)
                .get()
                .withLocalRepositoryBaseDirectories(localRepo)
                .build();
    }

    /**
     * Session for a real run. Caller must close it.
     *
     * <ul>
     *   <li>Checksum policy {@code warn}: Resolver keeps the file and reports problems to the
     *       listener, which classifies real mismatches as errors (design §8).</li>
     *   <li>Missing or broken POMs are not fatal inside Resolver; the walker reports them.</li>
     *   <li>Proxies come from the JVM's standard settings ({@code -Dhttps.proxyHost} etc.).</li>
     *   <li>Download threads and retries come from {@code --parallel} / {@code --retries}. The
     *       property names are Maven Resolver 2.x configuration keys; unknown keys are ignored by
     *       Resolver, so a wrong name would silently fall back to Resolver's default.</li>
     * </ul>
     */
    public static RepositorySystemSession.CloseableSession newSession(RepositorySystem system, RunConfig config,
                                                                      TransferListener listener) {
        return new SessionBuilderSupplier(system)
                .get()
                .withLocalRepositoryBaseDirectories(config.outDir())
                .setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_WARN)
                .setUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_NEVER)
                .setArtifactDescriptorPolicy(new SimpleArtifactDescriptorPolicy(false, false))
                .setTransferListener(listener)
                .setProxySelector(new JreProxySelector())
                // Do not filter requests by the repository's published prefix/group lists: behind a
                // Nexus group repository Central's list could hide artifacts from other members.
                .setConfigProperty("aether.remoteRepositoryFilter.prefixes", false)
                .setConfigProperty("aether.remoteRepositoryFilter.groupId", false)
                .setConfigProperty("aether.connector.basic.threads", config.parallelism())
                .setConfigProperty("aether.transport.http.retryHandler.count", config.retries())
                .build();
    }

    /** Remote repositories in lookup order: Central proxy, then Plugin Portal proxy (if configured). */
    public static List<RemoteRepository> remoteRepositories(RunConfig config) {
        Authentication auth = null;
        if (config.username() != null && !config.username().isBlank()) {
            auth = new AuthenticationBuilder()
                    .addUsername(config.username())
                    .addPassword(config.password() == null ? "" : config.password())
                    .build();
        }
        List<RemoteRepository> repos = new ArrayList<>();
        repos.add(remote(CENTRAL_ID, config.centralUrl(), auth));
        if (config.pluginsUrl() != null && !config.pluginsUrl().isBlank()) {
            repos.add(remote(PLUGINS_ID, config.pluginsUrl(), auth));
        }
        return repos;
    }

    private static RemoteRepository remote(String id, String url, Authentication auth) {
        return new RemoteRepository.Builder(id, "default", url)
                .setAuthentication(auth)
                .build();
    }
}
