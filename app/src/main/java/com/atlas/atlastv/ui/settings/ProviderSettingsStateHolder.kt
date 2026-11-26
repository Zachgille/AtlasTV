package com.atlas.atlastv.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atlas.atlastv.core.config.DebugConfig
import com.atlas.atlastv.core.security.UrlRedactor
import com.atlas.atlastv.data.catalog.CatalogImportResult
import com.atlas.atlastv.data.catalog.CatalogSyncRepository
import com.atlas.atlastv.data.catalog.CatalogSyncStatus
import com.atlas.atlastv.data.catalog.XtreamCatalogImporter
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.SafeCatalogRepository
import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.XtreamApiClient
import com.atlas.atlastv.xtream.network.XtreamLoadStage
import com.atlas.atlastv.ui.media.AtlasProviderImageIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File

class ProviderSettingsStateHolder(
    private val store: ProviderSettingsStore,
    private val scope: CoroutineScope,
    private val testConnection: suspend (XtreamCredentials) -> ProviderConnectionResult,
    private val syncCatalog: suspend (XtreamCredentials, (XtreamLoadStage) -> Unit) -> CatalogImportResult,
    private val syncTimeoutMs: Long = DefaultSyncTimeoutMs,
    private val bootstrapProviderSettings: ProviderSettingsSnapshot? = null,
    private val refreshGate: com.atlas.atlastv.data.catalog.CatalogRefreshGate = com.atlas.atlastv.data.catalog.CatalogRefreshGate.Shared,
) {
    private var acknowledgedSettings = ProviderSettingsSnapshot()
    private var pendingProviderSettings: ProviderSettingsSnapshot? = null
    private var pendingSyncStatus: ProviderSyncStatusSnapshot? = null
    private val syncSaveGuard = Any()
    private var initialSyncSaveFailed = false
    var state by mutableStateOf(loadInitialState().toUiState().copy(
        providerReadFailed = store.readIssue == ProviderSettingsReadIssue.Unavailable,
        syncStatusSaveFailed = initialSyncSaveFailed,
        actionMessage = when (store.readIssue) {
            ProviderSettingsReadIssue.Recovered -> "Recovered your saved provider details."
            ProviderSettingsReadIssue.Unavailable -> "Couldn’t read provider details. Try again."
            else -> null
        },
    ))
        private set

    fun updateBaseUrl(value: String) {
        if (!state.providerSaving) { pendingProviderSettings = null; state = state.copy(providerSaveFailed = false, actionMessage = null) }
        state = state.copy(baseUrlInput = value)
    }

    fun updateUsername(value: String) {
        if (!state.providerSaving) { pendingProviderSettings = null; state = state.copy(providerSaveFailed = false, actionMessage = null) }
        state = state.copy(usernameInput = value)
    }

    fun updatePassword(value: String) {
        if (!state.providerSaving) { pendingProviderSettings = null; state = state.copy(providerSaveFailed = false, actionMessage = null) }
        state = state.copy(passwordInput = value)
    }

    fun beginBackupEditing() {
        state = state.copy(backupUrlOne = acknowledgedSettings.alternateBaseUrls.getOrNull(0).orEmpty(),
            backupUrlTwo = acknowledgedSettings.alternateBaseUrls.getOrNull(1).orEmpty(), backupEditError = null)
    }
    fun updateBackupUrl(index: Int, value: String) {
        if (state.providerSaving) return
        pendingProviderSettings = null
        state = if (index == 0) state.copy(backupUrlOne = value, backupEditError = null, providerSaveFailed = false)
            else state.copy(backupUrlTwo = value, backupEditError = null, providerSaveFailed = false)
    }
    fun saveBackupServers() {
        if (state.providerSaving) return
        val addresses = com.atlas.atlastv.xtream.network.ProviderEndpointAddresses
        val primary = addresses.normalize(acknowledgedSettings.baseUrl)
        if (acknowledgedSettings.credentialsOrNull() == null || primary == null) {
            state = state.copy(backupEditError = "Save a valid provider address first."); return
        }
        val raw = listOf(state.backupUrlOne, state.backupUrlTwo).filter { it.isNotBlank() }
        val normalized = raw.map { addresses.normalize(it) }
        if (normalized.any { it == null }) {
            state = state.copy(backupEditError = "Use http:// or https:// addresses without account details."); return
        }
        val backups = normalized.filterNotNull().filter { it != primary }.distinct()
        val captured = acknowledgedSettings.copy(alternateBaseUrls = backups,
            preferredBaseUrl = acknowledgedSettings.preferredBaseUrl?.takeIf { it in backups || it == primary })
        pendingProviderSettings = captured
        state = state.copy(providerSaving = true, providerSaveFailed = false, backupEditError = null, actionMessage = "Saving backup servers…")
        scope.launch { persistProvider(captured) }
    }

    fun saveProviderSettings() {
        if (state.providerSaving) return
        val captured = pendingProviderSettings ?: currentSettings()
        if (state.providerReadFailed && captured.credentialsOrNull() == null) {
            retryProviderRead(); return
        }
        if (captured.credentialsOrNull() == null) { state = state.copy(actionMessage = "Missing provider fields."); return }
        pendingProviderSettings = captured
        state = state.copy(providerSaving = true, providerSaveFailed = false, actionMessage = "Saving provider details…")
        scope.launch { persistProvider(captured) }
    }

    suspend fun saveProviderSettingsNow() {
        if (state.providerSaving) return
        val captured = pendingProviderSettings ?: currentSettings()
        if (captured.credentialsOrNull() == null) { state = state.copy(actionMessage = "Missing provider fields."); return }
        pendingProviderSettings = captured
        state = state.copy(providerSaving = true, providerSaveFailed = false, actionMessage = "Saving provider details…")
        persistProvider(captured)
    }

    private suspend fun persistProvider(captured: ProviderSettingsSnapshot) = refreshGate.editProvider {
        val saved = withContext(Dispatchers.IO) { store.saveSettings(captured) }
        if (!saved) {
            state = state.copy(providerSaving = false, providerSaveFailed = true,
                actionMessage = "Couldn’t save provider details. Your saved account is unchanged.")
            return@editProvider
        }
        val persisted = withContext(Dispatchers.IO) { store.load() }
        acknowledgedSettings = persisted.settings
        val clearInputs = currentSettings() == captured
        pendingProviderSettings = null
        state = state.copy(providerSaving = false, providerSaveFailed = false, providerReadFailed = false,
            hasSavedCredentials = persisted.settings.credentialsOrNull() != null,
            redactedBaseUrl = persisted.settings.baseUrl.takeIf { it.isNotBlank() }?.let(UrlRedactor::redactHostAndSensitiveQuery),
            baseUrlInput = if (clearInputs) "" else state.baseUrlInput,
            usernameInput = if (clearInputs) "" else state.usernameInput,
            passwordInput = if (clearInputs) "" else state.passwordInput,
            actionMessage = "Provider settings saved.")
    }

    private fun retryProviderRead() {
        if (state.providerSaving) return
        state = state.copy(providerSaving = true)
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { store.load() }
            acknowledgedSettings = loaded.settings
            state = state.copy(providerSaving = false, providerReadFailed = store.readIssue == ProviderSettingsReadIssue.Unavailable,
                hasSavedCredentials = loaded.settings.credentialsOrNull() != null,
                actionMessage = if (store.readIssue == ProviderSettingsReadIssue.Unavailable) "Couldn’t read provider details. Try again." else "Provider details available.")
        }
    }

    fun retrySyncStatusSave() {
        val status = synchronized(syncSaveGuard) { pendingSyncStatus } ?: return
        scope.launch { withContext(Dispatchers.IO) {
            synchronized(syncSaveGuard) { if (pendingSyncStatus == status) persistSyncStatus(status) }
        } }
    }
    private fun persistSyncStatus(status: ProviderSyncStatusSnapshot) = synchronized(syncSaveGuard) {
        val saved = store.saveSyncStatus(status)
        pendingSyncStatus = status.takeUnless { saved }
        state = state.copy(syncStatusSaveFailed = !saved)
        com.atlas.atlastv.data.catalog.CatalogRefreshSignals.changed(finished = false)
    }

    fun testConnectionAsync() {
        scope.launch { testConnectionNow() }
    }

    fun syncMoviesAndShowsAsync(onFinished: (() -> Unit)? = null) {
        scope.launch {
            if (syncMoviesAndShowsNow()) onFinished?.invoke()
        }
    }

    suspend fun testConnectionNow() {
        val credentials = currentCredentialsOrSaved()
        if (credentials == null) {
            state = state.copy(connectionStatus = "failure", actionMessage = "Missing provider fields.")
            return
        }
        state = state.copy(connectionStatus = "testing", actionMessage = "Testing provider connection.")
        val result = withContext(Dispatchers.IO) { testConnection(credentials) }
        state = if (result.success) {
            state.copy(connectionStatus = "success", actionMessage = "Provider connection succeeded.")
        } else {
            state.copy(
                connectionStatus = "failure",
                actionMessage = "Provider connection failed: ${result.errorCategory ?: "Unknown"}",
            )
        }
    }

    suspend fun reloadSyncStatus() {
        val saved = withContext(Dispatchers.IO) { store.load() }
        state = state.copy(syncStatus = saved.syncStatus)
    }

    suspend fun syncMoviesAndShowsNow(): Boolean = refreshGate.refreshIfIdle {
        syncMoviesAndShowsLocked()
    } ?: run {
        state = state.copy(actionMessage = "Movies and Shows sync is already running.")
        false
    }

    private suspend fun syncMoviesAndShowsLocked(): Boolean {
        val now = System.currentTimeMillis()
        if (state.isSyncing && !state.syncStatus.isStaleSync(now)) {
            state = state.copy(actionMessage = "Movies and Shows sync is already running.")
            return false
        }
        val credentials = currentCredentialsOrSaved()
        if (credentials == null) {
            state = state.copy(actionMessage = "Missing provider fields.")
            return false
        }
        val previousStatus = withContext(Dispatchers.IO) { store.load().syncStatus }
        val startedAt = System.currentTimeMillis()
        val loadingStatus = previousStatus.copy(
            status = CatalogSyncStatus.Loading,
            lastStartedAtMs = startedAt,
            lastCompletedAtMs = previousStatus.lastCompletedAtMs,
            errorCategory = null,
            currentStage = "Starting Movies/Shows sync",
        )
        state = state.copy(syncStatus = loadingStatus, actionMessage = "Syncing Movies and Shows.")
        val timeoutWatchdog = startSyncTimeoutWatchdog(startedAt, loadingStatus)
        try {
            withContext(Dispatchers.IO) { persistSyncStatus(loadingStatus) }
            val result = withTimeoutOrNull(syncTimeoutMs) {
                withContext(Dispatchers.IO) {
                    try {
                        syncCatalog(credentials) { stage -> updateSyncStage(stage.label) }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { syncFailureResult(startedAt, "UnknownError") }
                }
            } ?: syncTimeoutResult(startedAt)
            val latestStatus = state.syncStatus
            if (
                latestStatus.status == CatalogSyncStatus.Failure &&
                latestStatus.errorCategory == "SyncTimeout" &&
                latestStatus.lastStartedAtMs == startedAt
            ) {
                state = state.copy(syncStatus = latestStatus, actionMessage = "Sync failed: SyncTimeout")
                return true
            }
            val status = result.toProviderSyncStatus(previousStatus = loadingStatus)
            withContext(Dispatchers.IO) { persistSyncStatus(status) }
            state = state.copy(
                syncStatus = status,
                actionMessage = if (result.status == CatalogSyncStatus.Success) {
                    "Sync complete: Movies and Shows synced."
                } else {
                    "Sync failed: ${result.errorCategory ?: "Unknown"}"
                },
            )
            return true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                if (state.syncStatus.status == CatalogSyncStatus.Loading && state.syncStatus.lastStartedAtMs == startedAt) {
                    val interrupted = syncFailureResult(startedAt, "SyncInterrupted").toProviderSyncStatus(loadingStatus)
                    persistSyncStatus(interrupted)
                    state = state.copy(syncStatus = interrupted, actionMessage = "Refresh interrupted. Your saved library is still available.")
                }
            }
            throw cancelled
        } finally { timeoutWatchdog.cancel() }
    }

    fun savedCredentialsOrNull(): XtreamCredentials? =
        acknowledgedSettings.credentialsOrNull()

    private fun currentSettings(): ProviderSettingsSnapshot =
        ProviderSettingsSnapshot(
            baseUrl = state.baseUrlInput,
            username = state.usernameInput,
            password = state.passwordInput,
        ).let { draft ->
            if (draft.baseUrl.trim().trimEnd('/') == acknowledgedSettings.baseUrl.trim().trimEnd('/') &&
                draft.username.trim() == acknowledgedSettings.username && draft.password == acknowledgedSettings.password)
                draft.copy(alternateBaseUrls = acknowledgedSettings.alternateBaseUrls, preferredBaseUrl = acknowledgedSettings.preferredBaseUrl)
            else draft
        }

    private fun currentCredentialsOrSaved(): XtreamCredentials? =
        currentSettings().credentialsOrNull() ?: savedCredentialsOrNull()

    private fun loadInitialState(): ProviderSettingsPersistedState {
        val persisted = loadWithBootstrappedProviderSettings()
        val status = persisted.syncStatus
        val startedAt = status.lastStartedAtMs
        val now = System.currentTimeMillis()
        if (status.status != CatalogSyncStatus.Loading || startedAt == null || now - startedAt <= syncTimeoutMs) {
            return persisted
        }
        val timeoutStatus = syncTimeoutResult(startedAt).toProviderSyncStatus(previousStatus = status)
        initialSyncSaveFailed = !store.saveSyncStatus(timeoutStatus)
        if (initialSyncSaveFailed) pendingSyncStatus = timeoutStatus
        return persisted.copy(syncStatus = timeoutStatus)
    }

    private fun loadWithBootstrappedProviderSettings(): ProviderSettingsPersistedState {
        ProviderSettingsBootstrap.bootstrapIfEmpty(store, bootstrapProviderSettings)
        return store.load().also { acknowledgedSettings = it.settings }
    }

    private fun updateSyncStage(stageLabel: String) {
        val current = state.syncStatus.takeIf { it.status == CatalogSyncStatus.Loading } ?: return
        val next = current.copy(currentStage = stageLabel)
        persistSyncStatus(next)
        state = state.copy(syncStatus = next, actionMessage = stageLabel)
    }

    private fun startSyncTimeoutWatchdog(
        startedAt: Long,
        loadingStatus: ProviderSyncStatusSnapshot,
    ): Job =
        scope.launch {
            delay(syncTimeoutMs)
            val latest = state.syncStatus
            if (latest.status == CatalogSyncStatus.Loading && latest.lastStartedAtMs == startedAt) {
                val timeoutStatus = syncTimeoutResult(startedAt).toProviderSyncStatus(previousStatus = loadingStatus)
                withContext(Dispatchers.IO) { persistSyncStatus(timeoutStatus) }
                state = state.copy(
                    syncStatus = timeoutStatus,
                    actionMessage = "Sync failed: SyncTimeout",
                )
            }
        }

    private fun syncTimeoutResult(startedAt: Long): CatalogImportResult = syncFailureResult(startedAt, "SyncTimeout")

    private fun syncFailureResult(startedAt: Long, category: String): CatalogImportResult =
        CatalogImportResult(
            status = CatalogSyncStatus.Failure,
            startedAtMs = startedAt,
            completedAtMs = System.currentTimeMillis(),
            moviesImported = 0,
            seriesImported = 0,
            excludedCategories = 0,
            excludedItems = 0,
            errorCategory = category,
            timings = com.atlas.atlastv.data.catalog.CatalogSyncTimings(
                totalDurationMs = System.currentTimeMillis() - startedAt,
            ),
        )

    private fun ProviderSyncStatusSnapshot.isStaleSync(nowMs: Long): Boolean {
        val startedAt = lastStartedAtMs ?: return true
        return nowMs - startedAt > syncTimeoutMs
    }

    companion object {
        private const val DefaultSyncTimeoutMs = 5L * 60L * 1000L

        fun create(context: android.content.Context, scope: CoroutineScope): ProviderSettingsStateHolder {
            val appContext = context.applicationContext
            val store = ProviderSettingsStore(File(appContext.filesDir, ProviderSettingsStore.FileName))
            com.atlas.atlastv.xtream.network.ProviderEndpointRecovery.Shared.persistUsing { credentials, base -> store.rememberEndpoint(credentials, base) }
            val apiClient = XtreamApiClient()
            val repository = SafeCatalogRepository(AtlasDatabaseProvider.database(appContext).safeCatalogDao())
            val syncRepository = CatalogSyncRepository(
                apiClient = apiClient,
                importer = XtreamCatalogImporter(
                    repository = repository,
                    providerImageIndex = AtlasProviderImageIndex.create(appContext),
                ),
            )
            return ProviderSettingsStateHolder(
                store = store,
                scope = scope,
                testConnection = { credentials -> apiClient.testConnection(credentials).toProviderConnectionResult() },
                syncCatalog = { credentials, stage ->
                    val result = syncRepository.syncMoviesAndSeries(credentials, stage)
                    if (result.status == CatalogSyncStatus.Success) {
                        // The app may be closed when a worker imports. Build its local rated shelves
                        // before publishing completion; never fill missing scores from the provider.
                        try {
                            val dao = AtlasDatabaseProvider.database(appContext).safeCatalogDao()
                            com.atlas.atlastv.data.discovery.ImdbDiscoveryIndexer(dao,
                                { com.atlas.atlastv.data.discovery.ImdbDiscoveryIndex.from(appContext) }).update()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Optional independent data retries on the next normal open. */ }
                    }
                    result
                },
                bootstrapProviderSettings = DebugConfig.debugXtreamCredentials?.toProviderSettingsSnapshot(),
            )
        }
    }
}

internal fun XtreamCredentials.toProviderSettingsSnapshot(): ProviderSettingsSnapshot =
    ProviderSettingsSnapshot(
        baseUrl = baseUrl,
        username = username,
        password = password,
        alternateBaseUrls = alternateBaseUrls,
        preferredBaseUrl = preferredBaseUrl,
    )

private fun ProviderSettingsPersistedState.toUiState(actionMessage: String? = null): ProviderSettingsUiState =
    ProviderSettingsUiState(
        baseUrlInput = "",
        usernameInput = "",
        passwordInput = "",
        hasSavedCredentials = settings.credentialsOrNull() != null,
        redactedBaseUrl = settings.baseUrl.takeIf { it.isNotBlank() }?.let(UrlRedactor::redactHostAndSensitiveQuery),
        syncStatus = syncStatus,
        actionMessage = actionMessage,
    )

private fun CatalogImportResult.toProviderSyncStatus(previousStatus: ProviderSyncStatusSnapshot): ProviderSyncStatusSnapshot {
    val next = toProviderSyncStatus()
    return when (status) {
        CatalogSyncStatus.Success -> next
        CatalogSyncStatus.Failure -> next.copy(
            lastSuccessfulSyncAtMs = previousStatus.lastSuccessfulSyncAtMs,
            moviesImported = previousStatus.moviesImported,
            seriesImported = previousStatus.seriesImported,
            excludedCategories = previousStatus.excludedCategories,
            excludedItems = previousStatus.excludedItems,
        )
        CatalogSyncStatus.Idle,
        CatalogSyncStatus.Loading -> next.copy(
            lastSuccessfulSyncAtMs = previousStatus.lastSuccessfulSyncAtMs,
            lastFailedSyncAtMs = previousStatus.lastFailedSyncAtMs,
        )
    }
}

private fun com.atlas.atlastv.xtream.network.XtreamApiResult.toProviderConnectionResult(): ProviderConnectionResult =
    when (this) {
        is com.atlas.atlastv.xtream.network.XtreamApiResult.Success -> ProviderConnectionResult(success = true)
        is com.atlas.atlastv.xtream.network.XtreamApiResult.Failure -> ProviderConnectionResult(success = false, errorCategory = category.name)
    }
