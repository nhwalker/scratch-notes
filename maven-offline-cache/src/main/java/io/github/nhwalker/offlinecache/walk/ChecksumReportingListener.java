package io.github.nhwalker.offlinecache.walk;

import io.github.nhwalker.offlinecache.report.Issue;
import io.github.nhwalker.offlinecache.report.Report;
import org.eclipse.aether.transfer.AbstractTransferListener;
import org.eclipse.aether.transfer.ChecksumFailureException;
import org.eclipse.aether.transfer.TransferEvent;

/**
 * Turns Resolver checksum events into report issues (design doc §7, §8).
 *
 * <p>The session uses the {@code warn} checksum policy, so Resolver accepts the file and calls
 * {@link #transferCorrupted}. A real mismatch carries the expected value and is an error.
 * Note (verified in tests with Resolver 2.0.25): a file with <em>no</em> upstream checksum is accepted
 * without any event, so it cannot be reported; its sidecars are generated afterwards. The info branch
 * below only covers other checksum problems Resolver may report.
 */
final class ChecksumReportingListener extends AbstractTransferListener {

    private final Report report;
    /** Resolver retries a corrupted download, firing the event once per attempt; report each file once. */
    private final java.util.Set<String> reported = java.util.concurrent.ConcurrentHashMap.newKeySet();

    ChecksumReportingListener(Report report) {
        this.report = report;
    }

    @Override
    public void transferCorrupted(TransferEvent event) {
        String resource = event.getResource().getRepositoryUrl() + event.getResource().getResourceName();
        if (!reported.add(resource)) {
            return;
        }
        Exception e = event.getException();
        if (e instanceof ChecksumFailureException cfe && cfe.getExpected() != null && !cfe.getExpected().isBlank()) {
            report.add(Issue.error("checksum-mismatch", resource,
                    "expected " + cfe.getExpectedKind() + " " + cfe.getExpected() + " but got " + cfe.getActual()));
        } else {
            report.add(Issue.info("checksum-missing-upstream", resource,
                    e == null ? "no checksum available" : e.getMessage()));
        }
    }
}
