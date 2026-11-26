package com.atlas.atlastv.ui.series

import com.google.gson.GsonBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

class SeriesPreferencesStore(
    private val file: File,
) {
    fun load(): SeriesPreferences =
        runCatching {
            if (!file.exists()) return SeriesPreferences()
            val snapshot = json.fromJson(file.readText(Charsets.UTF_8), SeriesPreferencesSnapshot::class.java)
            SeriesPreferences(
                selectedCategoryId = snapshot?.selectedCategoryId?.takeIf { it.isSafeSeriesPreferenceText() },
                selectedCategoryName = snapshot?.selectedCategoryName?.takeIf { it.isSafeSeriesPreferenceText() },
                selectedSeriesId = snapshot?.selectedSeriesId,
                selectedSeasonNumber = snapshot?.selectedSeasonNumber,
                selectedEpisodeId = snapshot?.selectedEpisodeId?.takeIf { it > 0 },
                selectedSeriesTitle = snapshot?.selectedSeriesTitle?.takeIf { it.isSafeSeriesPreferenceText() },
                selectedSeriesCategory = snapshot?.selectedSeriesCategory?.takeIf { it.isSafeSeriesPreferenceText() },
                sortMode = SeriesSortMode.fromStorageKey(snapshot?.sortMode),
                searchQuery = snapshot?.searchQuery.orEmpty()
                    .take(DefaultMaxSeriesSearchQueryLength)
                    .takeIf { it.isSafeSeriesPreferenceText() }
                    .orEmpty(),
            )
        }.getOrDefault(SeriesPreferences())

    fun save(preferences: SeriesPreferences) {
        val snapshot = SeriesPreferencesSnapshot(
            selectedCategoryId = preferences.selectedCategoryId?.takeIf { it.isSafeSeriesPreferenceText() },
            selectedCategoryName = preferences.selectedCategoryName?.takeIf { it.isSafeSeriesPreferenceText() },
            selectedSeriesId = preferences.selectedSeriesId,
            selectedSeasonNumber = preferences.selectedSeasonNumber,
            selectedEpisodeId = preferences.selectedEpisodeId?.takeIf { it > 0 },
            selectedSeriesTitle = preferences.selectedSeriesTitle?.takeIf { it.isSafeSeriesPreferenceText() },
            selectedSeriesCategory = preferences.selectedSeriesCategory?.takeIf { it.isSafeSeriesPreferenceText() },
            sortMode = preferences.sortMode.storageKey,
            searchQuery = preferences.searchQuery
                .take(DefaultMaxSeriesSearchQueryLength)
                .takeIf { it.isSafeSeriesPreferenceText() }
                .orEmpty(),
        )
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, "${file.name}.pending")
        try {
            pending.writeText(json.toJson(snapshot), Charsets.UTF_8)
            try {
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { pending.delete() }
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    companion object {
        const val FileName = "series_preferences.json"
        private val json = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    }
}

data class SeriesPreferences(
    val selectedCategoryId: String? = null,
    val selectedCategoryName: String? = null,
    val selectedSeriesId: Int? = null,
    val selectedSeriesTitle: String? = null,
    val selectedSeriesCategory: String? = null,
    val sortMode: SeriesSortMode = SeriesSortMode.Provider,
    val searchQuery: String = "",
    val selectedSeasonNumber: Int? = null,
    val selectedEpisodeId: Int? = null,
)

private data class SeriesPreferencesSnapshot(
    val selectedCategoryId: String? = null,
    val selectedCategoryName: String? = null,
    val selectedSeriesId: Int? = null,
    val selectedSeriesTitle: String? = null,
    val selectedSeriesCategory: String? = null,
    val sortMode: String? = null,
    val searchQuery: String? = null,
    val selectedSeasonNumber: Int? = null,
    val selectedEpisodeId: Int? = null,
)

private fun String.isSafeSeriesPreferenceText(): Boolean {
    val lowered = lowercase()
    return !lowered.contains("://") &&
        !lowered.contains("/series/") &&
        !lowered.contains("/movie/") &&
        !lowered.contains("/live/") &&
        !lowered.contains("password") &&
        !lowered.contains("username") &&
        !lowered.contains("token")
}

const val DefaultMaxSeriesSearchQueryLength = 80
