package com.example.e2e.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Placeholder e2e tests. Replace these with tests that exercise the real
 * system: HTTP calls, database queries, message bus round-trips, etc.
 * The target system's address arrives via -De2e.target.url at launch time.
 */
class SmokeE2eTest {

    private final String targetUrl =
            System.getProperty("e2e.target.url", "http://localhost:8080");

    @Test
    @DisplayName("target URL is configured")
    void targetUrlIsConfigured() {
        assertNotNull(targetUrl);
        assertTrue(targetUrl.startsWith("http"),
                "e2e.target.url should be an http(s) URL but was: " + targetUrl);
    }

    @Test
    @DisplayName("example: replace with a real end-to-end check")
    void exampleCheck() {
        // e.g. var response = httpClient.send(request(targetUrl + "/health"), ...);
        //      assertEquals(200, response.statusCode());
        assertTrue(true);
    }
}
