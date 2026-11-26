package com.atlas.atlastv.ui.settings

import com.google.gson.GsonBuilder
import java.io.File

enum class ProviderSettingsReadIssue { None, Recovered, Unavailable }

/** One process lock per file: account edits and sync-stage writes cannot lose each other. */
class ProviderSettingsStore(
    private val file: File,
    private val publish: (File, File) -> Unit = ::atomicProviderReplace,
) {
    private class Entry { var lastGood: ProviderSettingsPersistedState? = null }
    private val entry = entries.computeIfAbsent(file.absoluteFile.toPath().normalize().toString()) { Entry() }
    private val backup = File(file.parentFile, "${file.name}.lastgood")
    @Volatile var readIssue = ProviderSettingsReadIssue.None; private set

    fun load(): ProviderSettingsPersistedState = synchronized(entry) { loadLocked() }

    private fun read(source: File): ProviderSettingsPersistedState? = runCatching {
        if (!source.isFile) return@runCatching null
        val objectValue = com.google.gson.JsonParser.parseString(source.readText(Charsets.UTF_8)).asJsonObject
        require(objectValue.get("settings")?.isJsonObject == true)
        val settingsObject = objectValue.getAsJsonObject("settings")
        require(listOf("baseUrl", "username", "password").all { key ->
            settingsObject.get(key)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true
        })
        settingsObject.get("alternateBaseUrls")?.let { list ->
            require(list.isJsonArray && list.asJsonArray.size() <= 2)
            require(list.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString &&
                com.atlas.atlastv.xtream.network.ProviderEndpointAddresses.normalize(it.asString) != null })
        }
        settingsObject.get("preferredBaseUrl")?.let { require(it.isJsonNull || (it.isJsonPrimitive && it.asJsonPrimitive.isString)) }
        val value = json.fromJson(objectValue, ProviderSettingsPersistedState::class.java)
        require(value.syncStatus.status != null)
        value.safeForStorage()
    }.getOrNull()

    private fun loadLocked(): ProviderSettingsPersistedState {
        read(file)?.let { entry.lastGood = it; readIssue = ProviderSettingsReadIssue.None; return it }
        val good = entry.lastGood ?: read(backup)
        if (good != null) { entry.lastGood = good; readIssue = ProviderSettingsReadIssue.Recovered; return good }
        readIssue = if (file.exists() || backup.exists()) ProviderSettingsReadIssue.Unavailable else ProviderSettingsReadIssue.None
        return ProviderSettingsPersistedState()
    }

    fun saveSettings(settings: ProviderSettingsSnapshot): Boolean = synchronized(entry) {
        val old = loadLocked()
        var next = settings.safeForStorage()
        val previous = old.settings.credentialsOrNull()
        val incoming = next.credentialsOrNull()
        val addresses = com.atlas.atlastv.xtream.network.ProviderEndpointAddresses
        if (previous != null && incoming != null && addresses.normalize(previous.baseUrl) == addresses.normalize(incoming.baseUrl) &&
            previous.username == incoming.username && previous.password == incoming.password) {
            old.settings.preferredBaseUrl?.takeIf { it in addresses.configured(incoming) }?.let { next = next.copy(preferredBaseUrl = it) }
        }
        saveLocked(old.copy(settings = next))
    }
    fun saveSyncStatus(status: ProviderSyncStatusSnapshot): Boolean = synchronized(entry) {
        saveLocked(loadLocked().copy(syncStatus = status.safeForStorage()))
    }
    fun rememberEndpoint(credentials: com.atlas.atlastv.xtream.model.XtreamCredentials, baseUrl: String): Boolean = synchronized(entry) {
        val current = loadLocked()
        val account = current.settings.credentialsOrNull() ?: return@synchronized false
        val addresses = com.atlas.atlastv.xtream.network.ProviderEndpointAddresses
        if (addresses.normalize(account.baseUrl) != addresses.normalize(credentials.baseUrl) ||
            account.username != credentials.username || account.password != credentials.password ||
            addresses.configured(account) != addresses.configured(credentials)) return@synchronized false
        val selected = addresses.normalize(baseUrl)?.takeIf { it in addresses.configured(account) } ?: return@synchronized false
        if (current.settings.preferredBaseUrl == selected) return@synchronized true
        saveLocked(current.copy(settings = current.settings.copy(preferredBaseUrl = selected)))
    }

    private fun saveLocked(state: ProviderSettingsPersistedState): Boolean = runCatching {
        val safe = state.safeForStorage()
        file.parentFile?.mkdirs()
        // Preserve a validated previous commit before publishing; never copy corrupt/pending bytes.
        val previous = read(file) ?: read(backup) ?: entry.lastGood
        previous?.let { writeAtomic(backup, it) }
        writeAtomic(file, safe)
        entry.lastGood = safe
        readIssue = ProviderSettingsReadIssue.None
        // The primary is committed. A recovery-mirror failure must not falsely report Not saved.
        runCatching { writeAtomic(backup, safe) }
        true
    }.getOrDefault(false)

    private fun writeAtomic(target: File, state: ProviderSettingsPersistedState) {
        val temporary = File(target.parentFile, "${target.name}.pending")
        try {
            java.io.FileOutputStream(temporary).use {
                it.write(json.toJson(state).toByteArray(Charsets.UTF_8)); it.fd.sync()
            }
            publish(temporary, target)
        } finally { temporary.delete() }
    }

    fun clear() = synchronized(entry) {
        for (target in listOf(file, backup, File(file.parentFile,"${file.name}.pending"), File(file.parentFile,"${backup.name}.pending"))) {
            if (target.exists() && !target.delete()) throw java.io.IOException("Could not clear provider settings")
        }
        entry.lastGood = null; readIssue = ProviderSettingsReadIssue.None
    }

    companion object {
        const val FileName = "provider_settings.json"
        private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()
        private val json = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    }
}

internal fun atomicProviderReplace(temporary: File, target: File) {
    try { java.nio.file.Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
    catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        java.nio.file.Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}

object ProviderSettingsBootstrap {
    fun bootstrapIfEmpty(
        store: ProviderSettingsStore,
        settings: ProviderSettingsSnapshot?,
    ): Boolean {
        val bootstrap = settings?.takeIf { it.credentialsOrNull() != null } ?: return false
        if (store.load().settings.credentialsOrNull() != null || store.readIssue == ProviderSettingsReadIssue.Unavailable) return false
        return store.saveSettings(bootstrap)
    }
}

data class ProviderSettingsPersistedState(
    val settings: ProviderSettingsSnapshot = ProviderSettingsSnapshot(),
    val syncStatus: ProviderSyncStatusSnapshot = ProviderSyncStatusSnapshot(),
)

private fun ProviderSettingsPersistedState.safeForStorage(): ProviderSettingsPersistedState =
    copy(settings = settings.safeForStorage(), syncStatus = syncStatus.safeForStorage())

private fun ProviderSettingsSnapshot.safeForStorage(): ProviderSettingsSnapshot {
    val addresses = com.atlas.atlastv.xtream.network.ProviderEndpointAddresses
    val backups = alternateBaseUrls.mapNotNull(addresses::normalize)
        .filter { it != addresses.normalize(baseUrl) }.distinct().take(2)
    val allowed = listOfNotNull(addresses.normalize(baseUrl)) + backups
    return copy(
        baseUrl = baseUrl.trim(), username = username.trim(), password = password,
        alternateBaseUrls = backups,
        preferredBaseUrl = preferredBaseUrl?.let(addresses::normalize)?.takeIf { it in allowed },
        defaultOutputExtension = defaultOutputExtension?.trim()?.trimStart('.')
            ?.takeIf { it.isNotBlank() && !it.contains("/") && !it.contains(":") }?.take(MaxExtensionLength),
    )
}

private fun ProviderSyncStatusSnapshot.safeForStorage(): ProviderSyncStatusSnapshot =
    copy(
        errorCategory = errorCategory?.safeStatusText(),
        currentStage = currentStage?.safeStatusText(),
    )

private fun String.safeStatusText(): String =
    trim().let { value ->
        when {
            value.isBlank() -> ""
            value.contains("://") -> "Redacted"
            value.contains("/live/", ignoreCase = true) -> "Redacted"
            value.contains("/movie/", ignoreCase = true) -> "Redacted"
            value.contains("/series/", ignoreCase = true) -> "Redacted"
            else -> value.take(MaxSettingsTextLength)
        }
    }

private const val MaxSettingsTextLength = 512
private const val MaxExtensionLength = 12
