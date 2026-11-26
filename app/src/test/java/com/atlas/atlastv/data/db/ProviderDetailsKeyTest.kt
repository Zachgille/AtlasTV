package com.atlas.atlastv.data.db

import com.atlas.atlastv.xtream.model.XtreamCredentials
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ProviderDetailsKeyTest {
    private val account = XtreamCredentials("https://preview.invalid", "opaque user", "opaque password")

    @Test fun existingNamespaceKeysAndUnicodeGoldenKeyRemainExact() {
        assertEquals("f3ca1187fd13506d7897230897e2d351fc682ac8ce2aa713ddde87ea251a545e",
            providerDetailsCacheKey(account, "AtlasTV Home hero v1"))
        assertEquals("8da4354690fa460131bf473bf8225c94e5de8b020c53eea36bca29a4f58cf728",
            providerDetailsCacheKey(account, "AtlasTV Show details v1"))
        assertEquals("f4cb0eaad4fb32ecfa766134d524a4a60a2c37776a62ae739d404567da4f509c",
            providerDetailsCacheKey(account, "AtlasTV Movie details v1"))
        assertEquals("0d1620cab886172a80d34b44920a4b2b039347bf5898c51fe3876921cfc1a1cb",
            providerDetailsCacheKey(XtreamCredentials("https://unicode.invalid", "用户\u0000name", " 🔐пароль\n"), "namespace:😀"))
    }

    @Test fun legacyEncodingMatchesForOpaqueUnicodeEmptyAndLongTuples() {
        val values = listOf("", "a", "ab", " ", " literal spaces ", "\u0000", "用户", "🔐пароль", "e\u0301", "é", "x".repeat(4096))
        repeat(512) { index ->
            val credentials = XtreamCredentials(" https://example.invalid/$index/// ",
                values[index % values.size], values[(index / values.size) % values.size])
            val namespace = values[(index * 7) % values.size]
            val result = providerDetailsCacheKey(credentials, namespace)
            assertEquals(reference(credentials, namespace), result)
            assertTrue(result.matches(Regex("[0-9a-f]{64}")))
        }
    }

    @Test fun lengthFramingAndNamespacesKeepAccountsSeparate() {
        assertNotEquals(providerDetailsCacheKey(account.copy(username = "a", password = "bc"), "same"),
            providerDetailsCacheKey(account.copy(username = "ab", password = "c"), "same"))
        assertNotEquals(providerDetailsCacheKey(account, "Home"), providerDetailsCacheKey(account, "Movie"))
        assertNotEquals(providerDetailsCacheKey(account, "Home"), providerDetailsCacheKey(account.copy(baseUrl = "https://other.invalid"), "Home"))
    }

    @Test fun onlyEndpointCanonicalizationIsApplied() {
        val original = providerDetailsCacheKey(account, "Home")
        assertEquals(original, providerDetailsCacheKey(account.copy(baseUrl = " https://preview.invalid/// "), "Home"))
        assertNotEquals(original, providerDetailsCacheKey(account.copy(username = account.username + " "), "Home"))
        assertNotEquals(original, providerDetailsCacheKey(account.copy(password = account.password + " "), "Home"))
    }

    @Test fun outputIsLocaleIndependentAsciiHex() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale)
                assertEquals("f3ca1187fd13506d7897230897e2d351fc682ac8ce2aa713ddde87ea251a545e",
                    providerDetailsCacheKey(account, "AtlasTV Home hero v1"))
            }
        } finally { Locale.setDefault(previous) }
    }

    private fun reference(credentials: XtreamCredentials, namespace: String): String {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            for (value in listOf(namespace, credentials.baseUrl.trim().trimEnd('/'), credentials.username, credentials.password)) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                output.writeInt(bytes.size)
                output.write(bytes)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()).joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}
