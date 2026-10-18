package spike.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Isolates the open question of RepeatableExtendWithTest: with NO class-level annotation, is the extension still
 * registered for a method whose repeated @Req annotations are compiled into the container @Reqs (which carries no
 * @ExtendWith)? Outcome (JUnit 5.12.2): yes. RepeatableExtendWithTest.ReqExt (the extension @Req carries) ran once for
 * "single" and once for "repeated" (seeing R-01 and R-02). The Ext class below is registered by nothing and never runs;
 * it only shows that no other mechanism was involved (its result file stays empty).
 */
class RepeatableContainerOnlyTest {

  static final List<String> seen = new ArrayList<>();

  public static class Ext implements BeforeTestExecutionCallback {
    @Override public void beforeTestExecution(ExtensionContext ctx) {
      seen.add(ctx.getRequiredTestMethod().getName());
      System.out.println("### container-only: extension ran for " + ctx.getRequiredTestMethod().getName());
    }
  }

  @Test @RepeatableExtendWithTest.Req("S-01")
  void single() { }

  @Test @RepeatableExtendWithTest.Req("R-01") @RepeatableExtendWithTest.Req("R-02")
  void repeated() { }

  @AfterAll
  static void report() throws Exception {
    java.nio.file.Files.write(java.nio.file.Path.of("target/meta-container-results.txt"), seen);
    System.out.println("### container-only: methods that saw the extension = " + seen);
  }
}
