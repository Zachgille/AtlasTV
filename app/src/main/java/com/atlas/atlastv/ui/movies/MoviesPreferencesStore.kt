package com.atlas.atlastv.ui.movies

import com.google.gson.GsonBuilder
import java.io.File

class MoviesPreferencesStore(
    private val file: File,
) {
    fun load(): MoviesPreferences =
        runCatching {
            if (!file.exists()) return MoviesPreferences()
            val snapshot = json.fromJson(file.readText(Charsets.UTF_8), MoviesPreferencesSnapshot::class.java)
            MoviesPreferences(
                selectedCategoryId = snapshot?.selectedCategoryId?.takeIf { it.isSafeMoviesPreferenceText() },
                selectedCategoryName = snapshot?.selectedCategoryName?.takeIf { it.isSafeMoviesPreferenceText() },
                selectedMovieStreamId = snapshot?.selectedMovieStreamId,
                selectedMovieTitle = snapshot?.selectedMovieTitle?.takeIf { it.isSafeMoviesPreferenceText() },
                selectedMovieCategory = snapshot?.selectedMovieCategory?.takeIf { it.isSafeMoviesPreferenceText() },
                sortMode = MoviesSortMode.fromStorageKey(snapshot?.sortMode),
                searchQuery = snapshot?.searchQuery.orEmpty()
                    .take(DefaultMaxMoviesSearchQueryLength)
                    .takeIf { it.isSafeMoviesPreferenceText() }
                    .orEmpty(),
            )
        }.getOrDefault(MoviesPreferences())

    fun save(preferences: MoviesPreferences) {
        val snapshot = MoviesPreferencesSnapshot(
            selectedCategoryId = preferences.selectedCategoryId?.takeIf { it.isSafeMoviesPreferenceText() },
            selectedCategoryName = preferences.selectedCategoryName?.takeIf { it.isSafeMoviesPreferenceText() },
            selectedMovieStreamId = preferences.selectedMovieStreamId,
            selectedMovieTitle = preferences.selectedMovieTitle?.takeIf { it.isSafeMoviesPreferenceText() },
            selectedMovieCategory = preferences.selectedMovieCategory?.takeIf { it.isSafeMoviesPreferenceText() },
            sortMode = preferences.sortMode.storageKey,
            searchQuery = preferences.searchQuery
                .take(DefaultMaxMoviesSearchQueryLength)
                .takeIf { it.isSafeMoviesPreferenceText() }
                .orEmpty(),
        )
        file.writeText(json.toJson(snapshot), Charsets.UTF_8)
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    companion object {
        const val FileName = "movies_preferences.json"
        private val json = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    }
}

data class MoviesPreferences(
    val selectedCategoryId: String? = null,
    val selectedCategoryName: String? = null,
    val selectedMovieStreamId: Int? = null,
    val selectedMovieTitle: String? = null,
    val selectedMovieCategory: String? = null,
    val sortMode: MoviesSortMode = MoviesSortMode.Provider,
    val searchQuery: String = "",
)

private data class MoviesPreferencesSnapshot(
    val selectedCategoryId: String? = null,
    val selectedCategoryName: String? = null,
    val selectedMovieStreamId: Int? = null,
    val selectedMovieTitle: String? = null,
    val selectedMovieCategory: String? = null,
    val sortMode: String? = null,
    val searchQuery: String? = null,
)

private fun String.isSafeMoviesPreferenceText(): Boolean {
    val lowered = lowercase()
    return !lowered.contains("://") &&
        !lowered.contains("/movie/") &&
        !lowered.contains("/live/") &&
        !lowered.contains("password") &&
        !lowered.contains("username") &&
        !lowered.contains("token")
}

const val DefaultMaxMoviesSearchQueryLength = 80
