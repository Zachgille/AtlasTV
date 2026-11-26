package com.atlas.atlastv.core.config

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugSecretsTemplateTest {
    @Test
    fun debugSecretsExampleContainsOnlyFakeValues() {
        val content = String(
            Files.readAllBytes(findProjectRoot().resolve("debug-secrets.properties.example")),
            StandardCharsets.UTF_8,
        )

        assertTrue(content.contains("ATLASTV_DEBUG_PLAYLIST_URL=http://example.test/"))
        assertTrue(content.contains("username=fake_user"))
        assertTrue(content.contains("password=fake_password"))
        assertFalse(content.contains("http://example.test/get.php?username=abc"))
    }

    private fun findProjectRoot(): Path {
        val current = Paths.get("").toAbsolutePath()
        return generateSequence(current) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
    }
}
