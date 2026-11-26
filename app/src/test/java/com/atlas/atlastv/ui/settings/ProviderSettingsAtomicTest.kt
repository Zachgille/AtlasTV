package com.atlas.atlastv.ui.settings

import com.atlas.atlastv.data.catalog.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProviderSettingsAtomicTest {
    private fun fixture(check: (File) -> Unit) {
        val directory = java.nio.file.Files.createTempDirectory("provider-atomic-").toFile()
        try { check(File(directory,"settings.json")) } finally { directory.deleteRecursively() }
    }
    private val account = ProviderSettingsSnapshot("http://example.invalid","fixture-user","fixture-password")
    private val status = ProviderSyncStatusSnapshot(status = CatalogSyncStatus.Success,lastSuccessfulSyncAtMs = 100,
        moviesImported = 12,seriesImported = 9)
    private fun holder(store: ProviderSettingsStore, scope: CoroutineScope) = ProviderSettingsStateHolder(store,scope,
        { ProviderConnectionResult(true) }, { _, stage ->
            stage(com.atlas.atlastv.xtream.network.XtreamLoadStage.VodStreams)
            CatalogImportResult(CatalogSyncStatus.Success,100,200,15,10,0,0)
        })

    @Test fun failedPublicationPreservesPrimaryAndWholeLastGoodSnapshot() = fixture { file ->
        val store = ProviderSettingsStore(file); assertTrue(store.saveSettings(account)); assertTrue(store.saveSyncStatus(status))
        val original = file.readBytes()
        val broken = ProviderSettingsStore(file) { temporary,target ->
            if (target == file) throw IOException("fixture-password") else atomicProviderReplace(temporary,target)
        }
        assertFalse(broken.saveSettings(account.copy(password = "replacement")))
        assertArrayEquals(original,file.readBytes()); assertEquals(account,broken.load().settings)
        assertEquals(status,broken.load().syncStatus); assertFalse(File(file.parentFile,"${file.name}.pending").exists())
    }
    @Test fun partialPendingBytesCannotReplaceTheCommittedFile() = fixture { file ->
        val store = ProviderSettingsStore(file); store.saveSettings(account)
        File(file.parentFile,"${file.name}.pending").writeText("{truncated")
        assertEquals(account,ProviderSettingsStore(file).load().settings)
        assertTrue(store.saveSyncStatus(status)); assertFalse(File(file.parentFile,"${file.name}.pending").exists())
    }
    @Test fun freshFileIdentityRecoversValidatedDiskBackupWithoutAcceptingCorruptPrimary() = fixture { file ->
        val store = ProviderSettingsStore(file); store.saveSettings(account); store.saveSyncStatus(status)
        val copied = File(file.parentFile,"restart.json")
        copied.writeText("{truncated")
        File(file.parentFile,"restart.json.lastgood").writeBytes(File(file.parentFile,"settings.json.lastgood").readBytes())
        val reader = ProviderSettingsStore(copied)
        assertEquals(account,reader.load().settings); assertEquals(ProviderSettingsReadIssue.Recovered,reader.readIssue)
        assertTrue(reader.saveSyncStatus(status)); assertEquals(ProviderSettingsReadIssue.None,reader.readIssue)
        assertEquals(status,ProviderSettingsStore(copied).load().syncStatus)
    }
    @Test fun corruptReadPrefersTheNewestAcknowledgedInMemoryCopy() = fixture { file ->
        val store = ProviderSettingsStore(file); store.saveSettings(account); store.saveSyncStatus(status)
        file.writeText("{broken")
        assertEquals(status,ProviderSettingsStore(file).load().syncStatus)
        assertEquals(account,store.load().settings); assertEquals(ProviderSettingsReadIssue.Recovered,store.readIssue)
    }
    @Test fun malformedOrNullCredentialObjectsCannotBootstrapOverUnknownSavedData() = fixture { file ->
        for (text in listOf("{}","{broken", "{\"settings\":null}", "{\"settings\":{\"baseUrl\":null,\"username\":\"u\",\"password\":\"p\"}}")) {
            file.writeText(text); val store = ProviderSettingsStore(file)
            assertNull(store.load().settings.credentialsOrNull()); assertEquals(ProviderSettingsReadIssue.Unavailable,store.readIssue)
            assertFalse(ProviderSettingsBootstrap.bootstrapIfEmpty(store,account)); assertEquals(text,file.readText())
        }
    }
    @Test fun clearRemovesRecoveryAndPendingCopiesAndDoesNotReviveCredentials() = fixture { file ->
        val store = ProviderSettingsStore(file); store.saveSettings(account); store.saveSyncStatus(status)
        File(file.parentFile,"${file.name}.pending").writeText("partial")
        store.clear(); assertFalse(file.exists()); assertFalse(File(file.parentFile,"${file.name}.lastgood").exists())
        assertNull(ProviderSettingsStore(file).load().settings.credentialsOrNull())
    }
    @Test fun credentialLengthAndPasswordWhitespaceRemainOpaque() = fixture { file ->
        val settings = account.copy(username = "u".repeat(1500),password = "  "+"p".repeat(1800)+"  ")
        val store = ProviderSettingsStore(file); assertTrue(store.saveSettings(settings)); assertEquals(settings,store.load().settings)
    }
    @Test fun concurrentStoreInstancesPreserveBothAccountAndStatusFields() = fixture { file -> runBlocking {
        val a = ProviderSettingsStore(file); val b = ProviderSettingsStore(file); a.saveSettings(account)
        val writes = (1..20).map { index -> async(Dispatchers.IO) {
            if (index % 2 == 0) a.saveSettings(account.copy(password = "version-$index"))
            else b.saveSyncStatus(status.copy(moviesImported = index))
        } }; assertTrue(writes.awaitAll().all { it })
        val result = a.load(); assertTrue(result.settings.password.startsWith("version-")); assertTrue(result.syncStatus.moviesImported in 1..19)
        assertEquals(9,result.syncStatus.seriesImported)
    } }
    @Test fun failedBackupPublicationCannotTouchTheCurrentPrimary() = fixture { file ->
        val store = ProviderSettingsStore(file); store.saveSettings(account); val original = file.readBytes()
        val failing = ProviderSettingsStore(file) { _,_ -> throw IOException("fixture failure") }
        assertFalse(failing.saveSyncStatus(status)); assertArrayEquals(original,file.readBytes())
        assertEquals(account,failing.load().settings)
    }
    @Test fun asyncSaveAcknowledgementRetainsNewerDraftAndSuppressesDuplicateRequests() = fixture { file -> runBlocking {
        val started = CountDownLatch(1); val gate = CountDownLatch(1); var publications = 0
        val store = ProviderSettingsStore(file) { temporary,target ->
            if (target == file) { publications++; started.countDown(); check(gate.await(5,TimeUnit.SECONDS)) }
            atomicProviderReplace(temporary,target)
        }
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val holder = holder(store,scope); holder.updateBaseUrl(account.baseUrl); holder.updateUsername(account.username); holder.updatePassword(account.password)
            holder.saveProviderSettings(); holder.saveProviderSettings(); assertTrue(started.await(3,TimeUnit.SECONDS))
            assertTrue(holder.state.providerSaving); holder.updatePassword("newer-draft"); gate.countDown()
            withTimeout(5_000) { while (holder.state.providerSaving) delay(10) }
            assertEquals(1,publications); assertEquals(account,store.load().settings); assertEquals("newer-draft",holder.state.passwordInput)
            assertTrue(holder.state.hasSavedCredentials); assertFalse(holder.state.providerSaveFailed)
        } finally { gate.countDown(); scope.cancel() }
    } }
    @Test fun failedProviderSaveRetainsOldCredentialsThenRetryClearsOnlyAcknowledgedDraft() = fixture { file -> runBlocking {
        var fail = false
        val store = ProviderSettingsStore(file) { temporary,target ->
            if (fail && target == file) throw IOException("fixture-password")
            atomicProviderReplace(temporary,target)
        }; store.saveSettings(account); store.saveSyncStatus(status)
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val holder = holder(store,scope); holder.updateBaseUrl(account.baseUrl); holder.updateUsername(account.username); holder.updatePassword("replacement")
            fail = true; holder.saveProviderSettingsNow(); assertTrue(holder.state.providerSaveFailed)
            assertEquals(account,holder.savedCredentialsOrNull()?.toProviderSettingsSnapshot()); assertEquals("replacement",holder.state.passwordInput)
            assertFalse(holder.state.actionMessage!!.contains("fixture-password")); assertEquals(status,store.load().syncStatus)
            fail = false; holder.saveProviderSettingsNow(); assertFalse(holder.state.providerSaveFailed)
            assertEquals("replacement",store.load().settings.password); assertEquals("",holder.state.passwordInput)
            assertEquals(status,store.load().syncStatus)
        } finally { scope.cancel() }
    } }
    @Test fun syncMetadataFailureNeverCrashesOrDiscardsAccountAndRetryPersistsLatestResult() = fixture { file -> runBlocking {
        var fail = false
        val store = ProviderSettingsStore(file) { temporary,target ->
            if (fail && target == file) throw IOException("fixture-password")
            atomicProviderReplace(temporary,target)
        }; store.saveSettings(account); store.saveSyncStatus(status)
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val holder = holder(store,scope); fail = true; holder.syncMoviesAndShowsNow()
            assertEquals(CatalogSyncStatus.Success,holder.state.syncStatus.status); assertTrue(holder.state.syncStatusSaveFailed)
            assertEquals(account,store.load().settings); assertEquals(status,store.load().syncStatus)
            fail = false; holder.retrySyncStatusSave()
            withTimeout(5_000) { while (holder.state.syncStatusSaveFailed) delay(10) }
            assertEquals(15,store.load().syncStatus.moviesImported); assertEquals(10,store.load().syncStatus.seriesImported)
            assertEquals(account,store.load().settings)
        } finally { scope.cancel() }
    } }
    @Test fun firstSuccessfulSaveHasAValidatedDiskRecoveryCopy() = fixture { file ->
        val store = ProviderSettingsStore(file); assertTrue(store.saveSettings(account))
        val recovered = File(file.parentFile,"fresh-process.json")
        recovered.writeText("broken")
        File(file.parentFile,"fresh-process.json.lastgood").writeBytes(File(file.parentFile,"settings.json.lastgood").readBytes())
        assertEquals(account,ProviderSettingsStore(recovered).load().settings)
    }
    @Test fun postCommitMirrorFailureCannotReportAnAlreadySavedAccountAsFailed() = fixture { file ->
        var primaryCommitted = false
        val store = ProviderSettingsStore(file) { temporary,target ->
            if (primaryCommitted && target.name.endsWith(".lastgood")) throw IOException("fixture recovery-copy failure")
            atomicProviderReplace(temporary,target); if (target == file) primaryCommitted = true
        }
        assertTrue(store.saveSettings(account)); assertEquals(account,store.load().settings)
        assertEquals(account,ProviderSettingsStore(file).load().settings)
    }

    @Test fun duplicateRefreshCannotInvokeCompletionBeforeTheOriginalFinishes() = fixture { file -> runBlocking {
        val store = ProviderSettingsStore(file); store.saveSettings(account)
        val gate = CompletableDeferred<Unit>(); val requests = java.util.concurrent.atomic.AtomicInteger()
        val callbacks = java.util.concurrent.atomic.AtomicInteger(); val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val holder = ProviderSettingsStateHolder(store,scope,{ ProviderConnectionResult(true) }, { _,_ ->
                requests.incrementAndGet(); gate.await(); CatalogImportResult(CatalogSyncStatus.Success,100,200,1,1,0,0)
            })
            holder.syncMoviesAndShowsAsync { callbacks.incrementAndGet() }
            withTimeout(5_000) { while (requests.get() == 0) delay(10) }
            assertFalse(holder.syncMoviesAndShowsNow()); assertEquals(0,callbacks.get()); assertTrue(holder.state.isSyncing)
            gate.complete(Unit)
            withTimeout(5_000) { while (callbacks.get() == 0) delay(10) }
            assertEquals(1,callbacks.get()); assertEquals(1,requests.get()); assertFalse(holder.state.isSyncing)
        } finally { gate.complete(Unit); scope.cancel() }
    } }

}
