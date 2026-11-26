package com.atlas.atlastv.ui.settings

import com.atlas.atlastv.data.catalog.CatalogSyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProviderSettingsStoreTest {
    @Test
    fun savesAndLoadsProviderSettings() {
        val store = ProviderSettingsStore(tempFile())

        store.saveSettings(
            ProviderSettingsSnapshot(
                baseUrl = " http://provider.local ",
                username = " user ",
                password = "secret",
                defaultOutputExtension = " mp4 ",
            ),
        )

        val loaded = store.load().settings
        assertEquals("http://provider.local", loaded.baseUrl)
        assertEquals("user", loaded.username)
        assertEquals("secret", loaded.password)
        assertEquals("mp4", loaded.defaultOutputExtension)
    }

    @Test
    fun syncStatusStorageRedactsUnsafeErrorText() {
        val file = tempFile()
        val store = ProviderSettingsStore(file)

        store.saveSyncStatus(
            ProviderSyncStatusSnapshot(
                status = CatalogSyncStatus.Failure,
                lastStartedAtMs = 1L,
                lastCompletedAtMs = 2L,
                errorCategory = "http://provider.local/movie/user/pass/1.mp4",
            ),
        )

        val json = file.readText(Charsets.UTF_8)
        assertEquals("Redacted", store.load().syncStatus.errorCategory)
        assertFalse(json.contains("/movie/user"))
        assertFalse(json.contains("provider.local/movie"))
    }

    @Test
    fun bootstrapFillsEmptyProviderSettings() {
        val store = ProviderSettingsStore(tempFile())

        val changed = ProviderSettingsBootstrap.bootstrapIfEmpty(
            store = store,
            settings = ProviderSettingsSnapshot(
                baseUrl = "http://provider.local",
                username = "user",
                password = "secret",
            ),
        )

        val loaded = store.load().settings
        assertTrue(changed)
        assertEquals("http://provider.local", loaded.baseUrl)
        assertEquals("user", loaded.username)
        assertEquals("secret", loaded.password)
    }

    @Test
    fun bootstrapDoesNotOverwriteExistingProviderSettings() {
        val store = ProviderSettingsStore(tempFile())
        store.saveSettings(
            ProviderSettingsSnapshot(
                baseUrl = "http://existing.local",
                username = "existing",
                password = "existing-secret",
            ),
        )

        val changed = ProviderSettingsBootstrap.bootstrapIfEmpty(
            store = store,
            settings = ProviderSettingsSnapshot(
                baseUrl = "http://provider.local",
                username = "user",
                password = "secret",
            ),
        )

        val loaded = store.load().settings
        assertFalse(changed)
        assertEquals("http://existing.local", loaded.baseUrl)
        assertEquals("existing", loaded.username)
        assertEquals("existing-secret", loaded.password)
    }

    @Test
    fun bootstrapSkipsMissingCredentialsForReleaseLikePath() {
        val store = ProviderSettingsStore(tempFile())

        val changed = ProviderSettingsBootstrap.bootstrapIfEmpty(
            store = store,
            settings = ProviderSettingsSnapshot(),
        )

        assertFalse(changed)
        assertEquals(null, store.load().settings.credentialsOrNull())
    }

    @Test
    fun debugBootstrapSkipsWhenBuildIsNotDebug() {
        val store = ProviderSettingsStore(tempFile())

        val changed = ProviderSettingsDebugBootstrap.bootstrapIfEmpty(
            store = store,
            isDebugBuild = false,
            settings = ProviderSettingsSnapshot(
                baseUrl = "http://provider.local",
                username = "user",
                password = "secret",
            ),
        )

        assertFalse(changed)
        assertEquals(null, store.load().settings.credentialsOrNull())
    }

    private fun tempFile(): File =
        File.createTempFile("provider-settings", ".json").also {
            it.delete()
            it.deleteOnExit()
        }
}
