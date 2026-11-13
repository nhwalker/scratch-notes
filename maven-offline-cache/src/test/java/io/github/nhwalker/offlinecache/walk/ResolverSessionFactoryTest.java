package io.github.nhwalker.offlinecache.walk;

import io.github.nhwalker.offlinecache.RunConfig;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Smoke test: Maven Resolver can be wired without DI and with the output dir as local repo. No network. */
class ResolverSessionFactoryTest {

    @Test
    void createsSystemSessionAndRepositories(@TempDir Path out) {
        RunConfig config = new RunConfig(List.of(), out, out.resolve("report.json"),
                "https://nexus.example/repository/maven-central/", "https://nexus.example/repository/gradle-plugins/",
                "user", "secret", 4, 3, false, false, false);

        RepositorySystem system = ResolverSessionFactory.newRepositorySystem();
        try (RepositorySystemSession.CloseableSession session = ResolverSessionFactory.newSession(system, out)) {
            assertEquals(out.toAbsolutePath(),
                    session.getLocalRepository().getBasePath().toAbsolutePath());
        } finally {
            system.shutdown();
        }

        List<RemoteRepository> repos = ResolverSessionFactory.remoteRepositories(config);
        assertEquals(List.of(ResolverSessionFactory.CENTRAL_ID, ResolverSessionFactory.PLUGINS_ID),
                repos.stream().map(RemoteRepository::getId).toList());
        assertNotNull(repos.get(0).getAuthentication());
    }
}
