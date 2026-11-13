package io.github.nhwalker.offlinecache.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassifierHintTest {

    @Test
    void globMatching() {
        ClassifierHint h = new ClassifierHint("k", "io.grpc", "protoc-gen-*", List.of("x"), null, "t");
        assertTrue(h.matches("io.grpc", "protoc-gen-grpc-java"));
        assertFalse(h.matches("io.grpc", "grpc-core"));
        assertFalse(h.matches("ioXgrpc", "protoc-gen-grpc-java")); // dot is literal
        assertEquals("jar", h.extension());
    }

    @Test
    void wildcardEverything() {
        ClassifierHint h = new ClassifierHint("*:*", "*", "*", List.of("sources"), "jar", "t");
        assertTrue(h.matches("any.group", "any-artifact"));
    }

    @Test
    void questionMarkMatchesOneChar() {
        assertTrue(ClassifierHint.globToRegex("a?c").matcher("abc").matches());
        assertFalse(ClassifierHint.globToRegex("a?c").matcher("ac").matches());
    }
}
