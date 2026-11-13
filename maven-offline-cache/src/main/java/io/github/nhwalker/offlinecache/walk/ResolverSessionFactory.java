package io.github.nhwalker.offlinecache.walk;

import io.github.nhwalker.offlinecache.RunConfig;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.supplier.SessionBuilderSupplier;
import org.eclipse.aether.util.repository.AuthenticationBuilder;

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

    /** Caller must close the session. */
    public static RepositorySystemSession.CloseableSession newSession(RepositorySystem system, Path localRepo) {
        return new SessionBuilderSupplier(system)
                .get()
                .withLocalRepositoryBaseDirectories(localRepo)
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
