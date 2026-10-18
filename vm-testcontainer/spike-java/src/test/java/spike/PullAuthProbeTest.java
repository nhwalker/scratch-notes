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
    String outcome;
    try {
      String id = new RemoteDockerImage(DockerImageName.parse(ref)).get();
      boolean present = DockerClientFactory.instance().client().inspectImageCmd(ref).exec() != null;
      outcome = "PULLED id=" + id.substring(0, Math.min(19, id.length())) + " present=" + present;
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
