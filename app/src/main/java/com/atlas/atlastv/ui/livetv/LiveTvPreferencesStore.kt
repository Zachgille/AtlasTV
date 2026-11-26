package com.atlas.atlastv.ui.livetv

import com.google.gson.GsonBuilder
import java.io.File

class LiveTvPreferencesStore(
    private val file: File,
) {
    fun load(): LiveTvPreferences =
        runCatching {
            if (!file.exists()) return LiveTvPreferences()
            val snapshot = json.fromJson(file.readText(Charsets.UTF_8), PreferencesSnapshot::class.java)
            LiveTvPreferences(
                selectedCategoryId = snapshot?.selectedCategoryId,
                selectedChannelStreamId = snapshot?.selectedChannelStreamId,
                sortMode = LiveTvSortMode.fromStorageKey(snapshot?.sortMode),
                searchQuery = snapshot?.searchQuery.orEmpty(),
            )
        }.getOrDefault(LiveTvPreferences())

    fun save(preferences: LiveTvPreferences) {
        val snapshot = PreferencesSnapshot(
            selectedCategoryId = preferences.selectedCategoryId?.takeIf { it.isSafePreferenceText() },
            selectedChannelStreamId = preferences.selectedChannelStreamId,
            sortMode = preferences.sortMode.storageKey,
            searchQuery = preferences.searchQuery.take(MaxSearchQueryLength).takeIf { it.isSafePreferenceText() }.orEmpty(),
        )
        file.writeText(json.toJson(snapshot), Charsets.UTF_8)
    }

    fun clear() {
        if (file.exists()) {
            file.delete()
        }
    }

    companion object {
        const val FileName = "live_tv_preferences.json"
        private const val MaxSearchQueryLength = 80
        private val json = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    }
}

private fun String.isSafePreferenceText(): Boolean {
    val lowered = lowercase()
    return !lowered.contains("://") &&
        !lowered.contains("/live/") &&
        !lowered.contains("password") &&
        !lowered.contains("username") &&
        !lowered.contains("token")
}

data class LiveTvPreferences(
    val selectedCategoryId: String? = null,
    val selectedChannelStreamId: Int? = null,
    val sortMode: LiveTvSortMode = LiveTvSortMode.Provider,
    val searchQuery: String = "",
)

private data class PreferencesSnapshot(
    val selectedCategoryId: String? = null,
    val selectedChannelStreamId: Int? = null,
    val sortMode: String? = null,
    val searchQuery: String? = null,
)
