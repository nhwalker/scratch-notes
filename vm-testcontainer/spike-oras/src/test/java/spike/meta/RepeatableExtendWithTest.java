package spike.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.annotation.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Plan REQUIREMENTS.md §3.1: is an extension registered through @ExtendWith placed ON a @Repeatable custom
 * annotation, and does it see every repetition (incl. class-level ones)? Records what JUnit actually does.
 */
@RepeatableExtendWithTest.Req("CLASS-01")
class RepeatableExtendWithTest {

  @Documented @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD})
  @Repeatable(Reqs.class) @ExtendWith(ReqExt.class)
  public @interface Req { String value(); }

  @Documented @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD})
  public @interface Reqs { Req[] value(); }          // container WITHOUT @ExtendWith on purpose

  static final List<String> seen = new ArrayList<>();
  static int invocations = 0;

  public static class ReqExt implements BeforeTestExecutionCallback {  // note: RepeatableContainerOnlyTest reuses @Req but not this extension class
    @Override public void beforeTestExecution(ExtensionContext ctx) {
      invocations++;
      List<String> ids = new ArrayList<>();
      ctx.getTestMethod().ifPresent(m -> AnnotationSupport.findRepeatableAnnotations(m, Req.class).forEach(r -> ids.add(r.value())));
      ctx.getTestClass().ifPresent(c -> AnnotationSupport.findRepeatableAnnotations(c, Req.class).forEach(r -> ids.add(r.value())));
      seen.add(ctx.getRequiredTestMethod().getName() + "=" + ids + " (invocation " + invocations + ")");
      System.out.println("### " + seen.get(seen.size() - 1));
    }
  }

  @Test @Req("M-01")
  void single() { }

  @Test @Req("M-02") @Req("M-03")       // repeated → compiled into @Reqs, which has no @ExtendWith
  void repeated() { }

  @Test
  void none() { }                       // only the class-level @Req

  @AfterAll
  static void report() throws Exception {
    java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
    java.nio.file.Files.write(java.nio.file.Path.of("target/meta-results.txt"), seen);
    // the plan needs: every test method sees the extension exactly once, with all its ids
    assertEquals(3, seen.size(), seen.toString());
  }
}
