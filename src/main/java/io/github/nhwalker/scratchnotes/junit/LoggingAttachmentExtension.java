package io.github.nhwalker.scratchnotes.junit;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * stderr are interleaved into the same file, and every line in the file is
 * prefixed with {@code [STD] } or {@code [ERR] } identifying its source
 * stream; console output carries no prefixes. Every file line is
 * single-source: if one stream leaves a line unterminated and the other
 * stream writes next, the open line is broken with a newline and the new
 * stream starts its own tagged line. (One cosmetic consequence under parallel
 * interleaving: a println's text and its newline arrive separately, so an
 * orphaned newline can produce an empty tagged line.)
 *
 * <p><b>Threading model:</b> a single daemon writer thread owns all capture
 * state and all file I/O. Printing threads pass output through to the original
 * stream inline, then enqueue a copy of the bytes for the writer thread — a
 * test is never blocked by file writing. The one place a test thread waits is
 * {@code afterEach}, which awaits the close task so the test's file is
 * complete and closed when the test finishes (FIFO ordering guarantees all of
 * the test's pending writes land first). A capture file that fails to open is
 * reported to the original stderr but does not fail the test.
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
  private static final String STORE_KEY = "captureHandle";
  private static final Path OUTPUT_ROOT = Path.of("target", "test-logs");

  private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "logging-attachment-writer");
    thread.setDaemon(true);
    return thread;
  });

  // Touched only from tasks running on WRITER — thread-confined, so plain
  // collections with no synchronization.
  private static final Set<Capture> activeCaptures = new HashSet<>();
  private static final Map<String, Integer> invocationCounts = new HashMap<>();

  private static final AtomicBoolean INSTALLED = new AtomicBoolean();

  /** Identifies which console stream produced a chunk, and owns its file tag. */
  private enum Source {
    STD("[STD] "),
    ERR("[ERR] ");

    // Tags are pure ASCII, so these bytes are valid in any console charset.
    final byte[] prefix;

    Source(String tag) {
      this.prefix = tag.getBytes(StandardCharsets.US_ASCII);
    }
  }

  /** A per-test capture file plus its line state; writer-thread-only. */
  private static final class Capture {
    final OutputStream out;
    /** Stream that started the currently unterminated line; null at line start. */
    Source openLineSource;

    Capture(OutputStream out) {
      this.out = out;
    }
  }

  /** Links a test's open task to its close task; fields writer-thread-only. */
  private static final class CaptureHandle {
    Capture capture;
  }

  /**
   * Replaces System.out/err with tee streams, once per JVM. Installed lazily on
   * the first test so build-tool stream capture (e.g. Surefire's) set up before
   * tests run is wrapped rather than clobbered. The tees stay installed for the
   * JVM lifetime; with no active captures they are pure passthrough.
   */
  private static void installIfNeeded() {
    if (INSTALLED.compareAndSet(false, true)) {
      System.setOut(new PrintStream(new TeeOutputStream(System.out, Source.STD), true, System.out.charset()));
      System.setErr(new PrintStream(new TeeOutputStream(System.err, Source.ERR), true, System.err.charset()));
    }
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    installIfNeeded();
    Class<?> testClass = context.getRequiredTestClass();
    String methodName = context.getRequiredTestMethod().getName();
    PrintStream originalErr = System.err;

    CaptureHandle handle = new CaptureHandle();
    context.getStore(NAMESPACE).put(STORE_KEY, handle);
    // No waiting: FIFO ordering guarantees this open task runs before any
    // write task the test enqueues afterwards.
    WRITER.execute(() -> {
      String counterKey = testClass.getName() + "#" + methodName;
      int index = invocationCounts.merge(counterKey, 0, (old, ignored) -> old + 1);
      Path logFile = OUTPUT_ROOT
          .resolve(sanitize(testClass.getSimpleName()))
          .resolve(sanitize(methodName) + "_" + index + ".log");
      try {
        Files.createDirectories(logFile.getParent());
        handle.capture = new Capture(new BufferedOutputStream(Files.newOutputStream(logFile)));
        activeCaptures.add(handle.capture);
      } catch (IOException e) {
        originalErr.println("LoggingAttachmentExtension: could not open " + logFile + ": " + e);
      }
    });
  }

  @Override
  public void afterEach(ExtensionContext context) throws IOException {
    // Null-safe: afterEach runs even if beforeEach threw before storing a handle.
    CaptureHandle handle = context.getStore(NAMESPACE).remove(STORE_KEY, CaptureHandle.class);
    if (handle == null) {
      return;
    }
    try {
      // Awaited so the file is fully written and closed when the test ends;
      // all of this test's write tasks are queued ahead of this one.
      WRITER.submit(() -> {
        if (handle.capture != null) {
          activeCaptures.remove(handle.capture);
          try {
            handle.capture.out.close();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }
      }).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while closing test log file", e);
    } catch (ExecutionException e) {
      throw new IOException("Failed to close test log file", e.getCause());
    }
  }

  private static String sanitize(String name) {
    return name.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  /**
   * Forwards everything to the original stream inline, and enqueues a copy of
   * the bytes for the writer thread to fan out to every currently active
   * per-test capture file, stamping this stream's prefix at each line start.
   * Capture-side failures are swallowed so a bad capture file can never break
   * the real output path. Never closes the original or the capture streams;
   * captures are owned by afterEach.
   */
  private static final class TeeOutputStream extends OutputStream {
    private final PrintStream original;
    private final Source source;

    TeeOutputStream(PrintStream original, Source source) {
      this.original = original;
      this.source = source;
    }

    @Override
    public void write(int b) {
      original.write(b);
      byte[] chunk = {(byte) b};
      WRITER.execute(() -> fanOutChunk(source, chunk));
    }

    @Override
    public void write(byte[] buf, int off, int len) {
      original.write(buf, off, len);
      // Copy before enqueueing: PrintStream reuses its internal buffer, so the
      // array's contents may change before the writer thread runs.
      byte[] chunk = Arrays.copyOfRange(buf, off, off + len);
      WRITER.execute(() -> fanOutChunk(source, chunk));
    }

    @Override
    public void flush() {
      original.flush();
      WRITER.execute(TeeOutputStream::fanOutFlush);
    }

    @Override
    public void close() {
      // Intentionally a no-op: neither the original stream nor the capture
      // files may be closed through the tee.
    }

    /** Runs on the writer thread only. */
    private static void fanOutChunk(Source source, byte[] chunk) {
      for (Capture capture : activeCaptures) {
        try {
          writePrefixedLines(capture, source, chunk);
        } catch (IOException ignored) {
        }
      }
    }

    /** Runs on the writer thread only. */
    private static void fanOutFlush() {
      for (Capture capture : activeCaptures) {
        try {
          capture.out.flush();
        } catch (IOException ignored) {
        }
      }
    }

    private static void writePrefixedLines(Capture capture, Source source, byte[] chunk)
        throws IOException {
      int pos = 0;
      while (pos < chunk.length) {
        if (capture.openLineSource == null) {
          capture.out.write(source.prefix);
          capture.openLineSource = source;
        } else if (capture.openLineSource != source) {
          // The other stream owns the open line: break it and start a fresh
          // tagged line so every file line is single-source.
          capture.out.write('\n');
          capture.out.write(source.prefix);
          capture.openLineSource = source;
        }
        int newline = indexOf(chunk, (byte) '\n', pos);
        if (newline < 0) {
          capture.out.write(chunk, pos, chunk.length - pos);
          return;
        }
        capture.out.write(chunk, pos, newline - pos + 1);
        capture.openLineSource = null;
        pos = newline + 1;
      }
    }

    private static int indexOf(byte[] array, byte target, int from) {
      for (int i = from; i < array.length; i++) {
        if (array[i] == target) {
          return i;
        }
      }
      return -1;
    }
  }
}
