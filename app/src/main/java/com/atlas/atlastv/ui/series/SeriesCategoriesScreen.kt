package com.atlas.atlastv.ui.series

import androidx.compose.runtime.Composable
import com.atlas.atlastv.ui.browse.CatalogCategoriesScreen

@Composable
fun SeriesCategoriesScreen(holder: SeriesCategoriesStateHolder, revision: Long, onBack: () -> Unit, onCategory: (SeriesCategory) -> Unit, onNavigationChanged: () -> Unit = {}) {
    CatalogCategoriesScreen(holder, revision, "Show Categories", "show", "shows", "show", onBack, onCategory, onNavigationChanged)
}
