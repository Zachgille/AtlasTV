package com.atlas.atlastv.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlRedactorTest {
    @Test
    fun redactsUsernameAndPassword() {
        val original = "http://example.test/get.php?username=abc123&password=def456&type=m3u"

        val redacted = UrlRedactor.redact(original)

        assertEquals(
            "http://example.test/get.php?username=REDACTED&password=REDACTED&type=m3u",
            redacted,
        )
    }

    @Test
    fun redactsTokenKeyAndAuthParameters() {
        val original = "https://example.test/list?token=a&key=b&auth=c&user=d&pass=e"

        val redacted = UrlRedactor.redact(original)

        assertEquals(
            "https://example.test/list?token=REDACTED&key=REDACTED&auth=REDACTED&user=REDACTED&pass=REDACTED",
            redacted,
        )
    }

    @Test
    fun preservesNonSensitiveQueryParameters() {
        val original = "https://example.test/get.php?type=m3u&output=ts"

        val redacted = UrlRedactor.redact(original)

        assertEquals(original, redacted)
    }

    @Test
    fun redactsDebugPlaylistUrlFormat() {
        val original = "http://example.test/get.php?username=fake_user&password=fake_password&type=m3u_plus&output=ts"

        val redacted = UrlRedactor.redact(original)

        assertEquals(
            "http://example.test/get.php?username=REDACTED&password=REDACTED&type=m3u_plus&output=ts",
            redacted,
        )
    }

    @Test
    fun handlesMalformedUrlsConservatively() {
        val original = "not a url?username=abc&broken&token=def#fragment"

        val redacted = UrlRedactor.redact(original)

        assertEquals("not a url?username=REDACTED&broken&token=REDACTED#fragment", redacted)
    }

    @Test
    fun masksHostAndSensitiveQueryParameters() {
        val original = "http://example.test/player_api.php?username=abc&password=def&action=get_live_streams"

        val redacted = UrlRedactor.redactHostAndSensitiveQuery(original)

        assertEquals(
            "http://REDACTED_HOST/player_api.php?username=REDACTED&password=REDACTED&action=get_live_streams",
            redacted,
        )
    }

    @Test
    fun hostMaskingHandlesMalformedUrlsConservatively() {
        val redacted = UrlRedactor.redactHostAndSensitiveQuery("not a url?username=abc")

        assertEquals("REDACTED_URL", redacted)
    }
}
