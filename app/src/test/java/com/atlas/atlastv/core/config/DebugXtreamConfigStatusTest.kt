package com.atlas.atlastv.core.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugXtreamConfigStatusTest {
    @Test
    fun availableWhenAllRequiredFieldsArePresent() {
        val status = DebugXtreamConfigStatus(
            hasBaseUrl = true,
            hasUsername = true,
            hasPassword = true,
            redactedBaseUrl = "https://example.test",
            configSource = "debug-secrets.properties",
        )

        assertTrue(status.isAvailable)
    }

    @Test
    fun unavailableWhenBaseUrlIsMissing() {
        val status = DebugXtreamConfigStatus(
            hasBaseUrl = false,
            hasUsername = true,
            hasPassword = true,
            redactedBaseUrl = null,
            configSource = "debug-secrets.properties",
        )

        assertFalse(status.isAvailable)
    }

    @Test
    fun unavailableWhenUsernameIsMissing() {
        val status = DebugXtreamConfigStatus(
            hasBaseUrl = true,
            hasUsername = false,
            hasPassword = true,
            redactedBaseUrl = "https://example.test",
            configSource = "debug-secrets.properties",
        )

        assertFalse(status.isAvailable)
    }

    @Test
    fun unavailableWhenPasswordIsMissing() {
        val status = DebugXtreamConfigStatus(
            hasBaseUrl = true,
            hasUsername = true,
            hasPassword = false,
            redactedBaseUrl = "https://example.test",
            configSource = "debug-secrets.properties",
        )

        assertFalse(status.isAvailable)
    }
}
