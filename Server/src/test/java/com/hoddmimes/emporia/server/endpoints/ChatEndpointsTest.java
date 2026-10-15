package com.hoddmimes.emporia.server.endpoints;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatEndpointsTest {

    @Test
    void textIsRequiredAndBothAreLimitedInUtf8Bytes() {
        assertNull(ChatEndpoints.validate("hello", null));
        assertNull(ChatEndpoints.validate("hello", ""));
        assertNotNull(ChatEndpoints.validate(null, "t"));
        assertNotNull(ChatEndpoints.validate("   ", "t"));

        assertNull(ChatEndpoints.validate("x".repeat(512), "t".repeat(64)));
        assertNotNull(ChatEndpoints.validate("x".repeat(513), null));
        assertNotNull(ChatEndpoints.validate("x", "t".repeat(65)));
        // Counted in bytes, not characters: 'å' is 2 bytes in UTF-8
        assertNull(ChatEndpoints.validate("å".repeat(256), "å".repeat(32)));
        assertNotNull(ChatEndpoints.validate("å".repeat(257), null));
        assertNotNull(ChatEndpoints.validate("x", "å".repeat(33)));
        // Leading and trailing white space is removed first
        assertNull(ChatEndpoints.validate("  " + "x".repeat(512) + "\n", null));
    }
}
