package com.atlas.atlastv.ui.settings

import com.atlas.atlastv.data.catalog.CatalogImportResult
import com.atlas.atlastv.data.catalog.CatalogSyncStatus
import com.atlas.atlastv.xtream.network.ProviderEndpointRecovery
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProviderBackupSettingsTest {
    @get:Rule val temp = TemporaryFolder()
    private val account = ProviderSettingsSnapshot("http://primary.example", "opaque user", "密碼 / +?", "mkv",
        listOf("http://backup.example", "https://last.example"))
    private fun store(name: String = "settings.json") = ProviderSettingsStore(File(temp.root, name))
    @Test fun legacySettingsReopenAndWorkingBackupPersistsWithoutChangingAccountIdentity() {
        val store = store(); assertTrue(store.saveSettings(account))
        val credentials = account.credentialsOrNull()!!
        val key = com.atlas.atlastv.ui.series.providerDetailsKey(credentials)
        assertTrue(store.rememberEndpoint(credentials, "http://backup.example"))
        val loaded = store.load().settings
        assertEquals(account.copy(preferredBaseUrl = "http://backup.example"), loaded)
        assertEquals(key, com.atlas.atlastv.ui.series.providerDetailsKey(loaded.credentialsOrNull()!!))
        assertEquals("http://backup.example", ProviderEndpointRecovery().activeBaseUrl(loaded.credentialsOrNull()!!))
        val file = File(temp.root, "legacy.json")
        file.writeText("""{"settings":{"baseUrl":"http://primary.example","username":"u","password":"p"},"syncStatus":{"status":"Idle"}}""")
        assertTrue(ProviderSettingsStore(file).load().settings.alternateBaseUrls.isEmpty())
    }
    @Test fun oldResponsesRemovedHostsAndChangedAccountsCannotOverwriteCurrentSettings() {
        val store = store(); store.saveSettings(account)
        val old = account.credentialsOrNull()!!
        assertFalse(store.rememberEndpoint(old, "http://unlisted.example"))
        store.saveSettings(account.copy(alternateBaseUrls = listOf("https://last.example")))
        assertFalse(store.rememberEndpoint(old, "http://backup.example"))
        val changed = account.copy(username = "another account", preferredBaseUrl = null)
        store.saveSettings(changed)
        assertFalse(store.rememberEndpoint(old, "https://last.example")); assertEquals(changed, store.load().settings)
    }
    @Test fun backupEditsPreserveConcurrentPreferredChoiceAndRemovingItClearsTheChoice() {
        val store = store(); store.saveSettings(account)
        store.rememberEndpoint(account.credentialsOrNull()!!, "http://backup.example")
        assertTrue(store.saveSettings(account)) // A UI draft predating recovery must not revert the route.
        assertEquals("http://backup.example", store.load().settings.preferredBaseUrl)
        assertTrue(store.saveSettings(account.copy(alternateBaseUrls = emptyList())))
        assertNull(store.load().settings.preferredBaseUrl)
    }
    @Test fun malformedBackupPayloadRecoversTheLastGoodConfigAndFailedRouteSaveKeepsIt() {
        val file = File(temp.root, "fault.json"); var fail = false
        val store = ProviderSettingsStore(file) { from, to ->
            if (fail && to == file) throw java.io.IOException("fixture")
            atomicProviderReplace(from, to)
        }
        store.saveSettings(account); fail = true
        assertFalse(store.rememberEndpoint(account.credentialsOrNull()!!, "http://backup.example"))
        assertEquals(account, store.load().settings)
        file.writeText("""{"settings":{"baseUrl":"http://primary.example","username":"u","password":"p","alternateBaseUrls":null},"syncStatus":{"status":"Idle"}}""")
        assertEquals(account, store.load().settings); assertEquals(ProviderSettingsReadIssue.Recovered, store.readIssue)
    }
    @Test fun normalBackupActionSavesWithoutRetypingCredentialsAndRejectsInvalidDrafts() = runBlocking {
        val store = store(); store.saveSettings(account.copy(alternateBaseUrls = emptyList()))
        val holder = ProviderSettingsStateHolder(store, this, { ProviderConnectionResult(true) },
            { _, _ -> CatalogImportResult(CatalogSyncStatus.Success, 1, 2, 1, 1, 0, 0) })
        holder.beginBackupEditing(); holder.updateBackupUrl(0, "file:///invalid"); holder.saveBackupServers()
        assertNotNull(holder.state.backupEditError); assertFalse(holder.state.providerSaving)
        assertTrue(store.load().settings.alternateBaseUrls.isEmpty())
        holder.updateBackupUrl(0, " HTTP://BACKUP.EXAMPLE:80/ "); holder.updateBackupUrl(1, "http://backup.example")
        holder.saveBackupServers()
        withTimeout(5_000) { while (holder.state.providerSaving) delay(5) }
        assertEquals(listOf("http://backup.example"), store.load().settings.alternateBaseUrls)
        assertEquals(account.username, holder.savedCredentialsOrNull()!!.username)
        assertEquals(account.password, holder.savedCredentialsOrNull()!!.password)
        assertEquals("", holder.state.passwordInput)
        holder.beginBackupEditing(); holder.updateBackupUrl(0, ""); holder.updateBackupUrl(1, ""); holder.saveBackupServers()
        withTimeout(5_000) { while (holder.state.providerSaving) delay(5) }
        assertTrue(store.load().settings.alternateBaseUrls.isEmpty())
    }
}
