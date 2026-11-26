package com.atlas.atlastv.ui.movies

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** UI context owned by the Movies holder, including player composition changes. No persistent writes. */
class MovieCategoryNavigationState {
    private var trail by mutableStateOf(emptyList<MovieItem>())
    var restoreMovieId by mutableStateOf<Int?>(null)
    fun openChild(parent: MovieItem) { trail = (trail + parent).takeLast(32); restoreMovieId = null }
    fun returnParent(childId: Int?): MovieItem? {
        val parent = trail.lastOrNull() ?: return null
        trail = trail.dropLast(1); restoreMovieId = childId
        return parent
    }
    fun clear() { trail = emptyList(); restoreMovieId = null }
}
