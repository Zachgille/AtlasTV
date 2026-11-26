package com.atlas.atlastv.ui.mylist

import androidx.compose.runtime.*
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.atlas.atlastv.ui.browse.*

@Composable
fun MyListScreen(browser: MyListBrowser, revision: Long, onBack: () -> Unit, onItem: (MyListItem) -> Unit,
    statusMessage: String? = null, onRetrySave: (() -> Unit)? = null) {
    key(browser.grid) {
        CatalogGridScreen(browser.grid, revision, "My List", "title", "titles", "my_list", { it.title }, { it.subtitle }, { it.image },
            onBack, onItem, emptyMessage = when (browser.filter) {
                MyListFilter.All -> "Your list is empty. Add movies or shows from their details."
                MyListFilter.Movies -> "No saved movies yet. Add movies from their details."
                MyListFilter.Shows -> "No saved shows yet. Add shows from their details."
            }, statusMessage = statusMessage, onRetryStatus = onRetrySave, showJumpControls = false, showSubtitle = true,
            headerActions = {
                MyListFilter.entries.forEach { filter ->
                    BrowseSearchButton(filter.label, { browser.selectFilter(filter) }, "my_list_filter_${filter.name.lowercase()}",
                        Modifier.then(if (browser.filter == filter) Modifier.border(1.dp, com.atlas.atlastv.ui.theme.AtlasAccent, RoundedCornerShape(8.dp)) else Modifier)
                            .semantics { selected = browser.filter == filter })
                }
            })
    }
}
