package io.github.nhwalker.offlinecache.report;

import java.util.Objects;

/**
 * One reported outcome.
 *
 * @param severity level
 * @param code     stable machine-readable code, e.g. {@code pom-not-found}
 * @param subject  what it is about: a coordinate, file path, catalog alias or catalog file
 * @param message  human-readable explanation
 */
public record Issue(Severity severity, String code, String subject, String message) {

    public Issue {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(message, "message");
    }

    public static Issue error(String code, String subject, String message) {
        return new Issue(Severity.ERROR, code, subject, message);
    }

    public static Issue warning(String code, String subject, String message) {
        return new Issue(Severity.WARNING, code, subject, message);
    }

    public static Issue info(String code, String subject, String message) {
        return new Issue(Severity.INFO, code, subject, message);
    }

    @Override
    public String toString() {
        return severity + " [" + code + "] " + subject + ": " + message;
    }
}
