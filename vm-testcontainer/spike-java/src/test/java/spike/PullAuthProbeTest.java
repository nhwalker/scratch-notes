package spike;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.RemoteDockerImage;
import org.testcontainers.utility.DockerImageName;

/**
 * Engine-side pull of a private containerDisk image (the prerequisite for an image mount), through Testcontainers.
 * PULL_SCENARIO names where credentials live; the outcome is recorded, not asserted.
 */
class PullAuthProbeTest {

  static final Path RESULTS = Path.of("target", "pull-results.txt");

  @Test
  void pullPrivateImage() throws IOException {
    String ref = System.getenv("PULL_REF");
    Assumptions.assumeTrue(ref != null, "PULL_REF not set");
    String scenario = System.getenv().getOrDefault("PULL_SCENARIO", "?");
    String user = System.getenv("PULL_AUTH_USER");
    String outcome;
    try {
      String id;
      if (user != null) {
        // T27c candidate: explicit credentials on the pull itself (no config.json, no podman login)
        DockerImageName name = DockerImageName.parse(ref);
        var auth = new com.github.dockerjava.api.model.AuthConfig()
            .withRegistryAddress(name.getRegistry()).withUsername(user).withPassword(System.getenv("PULL_AUTH_PASS"));
        DockerClientFactory.instance().client().pullImageCmd(ref).withAuthConfig(auth)
            .exec(new com.github.dockerjava.api.command.PullImageResultCallback())
            .awaitCompletion(5, java.util.concurrent.TimeUnit.MINUTES);
        id = DockerClientFactory.instance().client().inspectImageCmd(ref).exec().getId();
      } else {
        id = new RemoteDockerImage(DockerImageName.parse(ref)).get();
      }
      boolean present = DockerClientFactory.instance().client().inspectImageCmd(ref).exec() != null;
      outcome = "PULLED id=" + id.substring(0, Math.min(19, id.length())) + " present=" + present
          + (user != null ? " via=explicit-AuthConfig" : " via=RemoteDockerImage");
    } catch (Exception e) {
      StringBuilder chain = new StringBuilder("FAILED");
      for (Throwable x = e; x != null; x = x.getCause()) {
        chain.append(" <- ").append(x.getClass().getSimpleName()).append(": ").append(x.getMessage());
      }
      outcome = chain.toString().replace("\n", " ");
    }
    String rec = "pull[" + scenario + "] " + ref + " -> " + outcome + System.lineSeparator();
    Files.createDirectories(RESULTS.getParent());
    Files.writeString(RESULTS, rec, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    System.out.print("### " + rec);
  }
}
