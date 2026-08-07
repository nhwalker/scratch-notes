package io.github.nhwalker.scratchnotes.junit;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;

/**
 * JUnit 5 extension that captures everything written to {@link System#out} and
 * {@link System#err} during each test into a per-test log file, while still
 * passing all output through to the original streams.
 *
 * <p>Register it on a test class:
 *
 * <pre>{@code
 * @ExtendWith(LoggingAttachmentExtension.class)
 * class MyTest { ... }
 * }</pre>
 *
 * <p>Log files are written to {@code target/test-logs/<TestClassSimpleName>/},
 * one file per test invocation, named {@code <methodName>_<index>.log} where
 * the index starts at 0 and increments for each invocation of the same method
 * (so parameterized and repeated tests get unique file names). Both stdout and
 * stderr are interleaved into the same file.
 *
 * <p><b>Parallel execution</b> is supported with a caveat: output is not
 * attributed to threads, so while two tests run concurrently, everything
 * printed goes to <em>both</em> tests' files.
 *
 * <p>Tests that call {@link System#setOut}/{@link System#setErr} themselves
 * replace the tee streams and bypass capture for the remainder of the run.
 */
public final class LoggingAttachmentExtension implements BeforeEachCallback, AfterEachCallback {

  private static final Namespace NAMESPACE = Namespace.create(LoggingAttachmentExtension.class);
  private static final String STORE_KEY = "captureStream";
  private static final Path OUTPUT_ROOT = Path.of("target", "test-logs");

  /** Guards writes to and membership changes of {@link #ACTIVE_CAPTURES}. */
  private static final Object CAPTURE_LOCK = new Object();

  private static final Set<OutputStream> ACTIVE_CAPTURES = ConcurrentHashMap.newKeySet();
  private static final ConcurrentHashMap<String, AtomicInteger> INVOCATION_COUNTS = new ConcurrentHashMap<>();
  private static final AtomicBoolean INSTALLED = new AtomicBoolean();

  /**
   * Replaces System.out/err with tee streams, once per JVM. Installed lazily on
   * the first test so build-tool stream capture (e.g. Surefire's) set up before
   * tests run is wrapped rather than clobbered. The tees stay installed for the
   * JVM lifetime; with no active captures they are pure passthrough.
   */
  private static void installIfNeeded() {
    if (INSTALLED.compareAndSet(false, true)) {
      System.setOut(new PrintStream(new TeeOutputStream(System.out), true, System.out.charset()));
      System.setErr(new PrintStream(new TeeOutputStream(System.err), true, System.err.charset()));
    }
  }

  @Override
  public void beforeEach(ExtensionContext context) throws IOException {
    installIfNeeded();
    Path logFile = resolveLogFile(context);
    Files.createDirectories(logFile.getParent());
    OutputStream capture = new BufferedOutputStream(Files.newOutputStream(logFile));
    context.getStore(NAMESPACE).put(STORE_KEY, capture);
    synchronized (CAPTURE_LOCK) {
      ACTIVE_CAPTURES.add(capture);
    }
  }

  @Override
  public void afterEach(ExtensionContext context) throws IOException {
    // Null-safe: afterEach runs even if beforeEach threw before storing a stream.
    OutputStream capture = context.getStore(NAMESPACE).remove(STORE_KEY, OutputStream.class);
    if (capture == null) {
      return;
    }
    synchronized (CAPTURE_LOCK) {
      ACTIVE_CAPTURES.remove(capture);
    }
    System.out.flush();
    System.err.flush();
    synchronized (CAPTURE_LOCK) {
      capture.close();
    }
  }

  private static Path resolveLogFile(ExtensionContext context) {
    Class<?> testClass = context.getRequiredTestClass();
    String methodName = context.getRequiredTestMethod().getName();
    String counterKey = testClass.getName() + "#" + methodName;
    int index = INVOCATION_COUNTS.computeIfAbsent(counterKey, k -> new AtomicInteger()).getAndIncrement();
    return OUTPUT_ROOT
        .resolve(sanitize(testClass.getSimpleName()))
        .resolve(sanitize(methodName) + "_" + index + ".log");
  }

  private static String sanitize(String name) {
    return name.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  /**
   * Forwards everything to the original stream and, additionally, to every
   * currently active per-test capture file. Capture-side failures are swallowed
   * so a bad capture file can never break the real output path. Never closes
   * the original or the capture streams; captures are owned by afterEach.
   */
  private static final class TeeOutputStream extends OutputStream {
    private final PrintStream original;

    TeeOutputStream(PrintStream original) {
      this.original = original;
    }

    @Override
    public void write(int b) {
      original.write(b);
      synchronized (CAPTURE_LOCK) {
        for (OutputStream capture : ACTIVE_CAPTURES) {
          try {
            capture.write(b);
          } catch (IOException ignored) {
          }
        }
      }
    }

    @Override
    public void write(byte[] buf, int off, int len) {
      original.write(buf, off, len);
      synchronized (CAPTURE_LOCK) {
        for (OutputStream capture : ACTIVE_CAPTURES) {
          try {
            capture.write(buf, off, len);
          } catch (IOException ignored) {
          }
        }
      }
    }

    @Override
    public void flush() {
      original.flush();
      synchronized (CAPTURE_LOCK) {
        for (OutputStream capture : ACTIVE_CAPTURES) {
          try {
            capture.flush();
          } catch (IOException ignored) {
          }
        }
      }
    }

    @Override
    public void close() {
      // Intentionally a no-op: neither the original stream nor the shared
      // capture files may be closed through the tee.
    }
  }
}
