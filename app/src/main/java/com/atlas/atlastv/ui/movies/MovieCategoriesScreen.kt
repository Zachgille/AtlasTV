package com.atlas.atlastv.ui.movies

import androidx.compose.runtime.Composable
import com.atlas.atlastv.ui.browse.CatalogCategoriesScreen

@Composable
fun MovieCategoriesScreen(holder: MovieCategoriesStateHolder, revision: Long, onBack: () -> Unit, onCategory: (MovieCategory) -> Unit, onNavigationChanged: () -> Unit = {}) {
    CatalogCategoriesScreen(holder, revision, "Movie Categories", "movie", "movies", "movie", onBack, onCategory, onNavigationChanged)
}
