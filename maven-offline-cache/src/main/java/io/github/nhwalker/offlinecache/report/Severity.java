package io.github.nhwalker.offlinecache.report;

/**
 * Severity levels, see design doc §8.
 *
 * <ul>
 *   <li>{@link #ERROR} — a consumer build could break; the run fails at the end.</li>
 *   <li>{@link #WARNING} — probably a configuration mistake; reported, run still succeeds.</li>
 *   <li>{@link #INFO} — expected outcome; listed in report.json only.</li>
 * </ul>
 */
public enum Severity {
    ERROR,
    WARNING,
    INFO
}
