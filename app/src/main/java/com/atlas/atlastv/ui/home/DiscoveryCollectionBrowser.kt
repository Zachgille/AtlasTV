package com.atlas.atlastv.ui.home

import androidx.compose.runtime.*
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.data.discovery.DiscoveryCollectionSpec
import com.atlas.atlastv.ui.browse.*

/** One bounded collection holder survives details/player composition; a new collection resets its context. */
class DiscoveryCollectionBrowser(private val dao: SafeCatalogDao, private val clock: () -> Long = {System.currentTimeMillis()}) {
    var spec by mutableStateOf<DiscoveryCollectionSpec?>(null);private set
    var grid: CatalogGridStateHolder<HomeCard>? = null;private set
    fun open(collection: DiscoveryCollectionSpec) {
        if(spec!=collection) {
            spec=collection
            grid=CatalogGridStateHolder(CatalogGridSource {index,selected ->
                val page=dao.discoveryWindow(collection,clock(),index,selected)
                CatalogGridWindow(page.total,page.offset,page.anchor,page.items.map {entity ->
                    if(collection.type=="movie") entity.toMovieItem("room://safe_catalog/vod/${entity.itemId}").toHomeMovieCard(collection.id,"Open details")
                    else entity.toSeriesItem().toHomeShowCard(collection.id,"Open details")
                })
            },{it.streamId},"Couldn't load this collection. Try again.",allowEmptyRefresh=true)
        }
    }
}

@Composable
fun DiscoveryCollectionScreen(browser: DiscoveryCollectionBrowser,revision: Long,onBack: () -> Unit,onItem: (HomeCard) -> Unit) {
    val spec=browser.spec ?: return
    val grid=browser.grid ?: return
    CatalogGridScreen(grid,revision,spec.title,if(spec.type=="movie") "movie" else "show",if(spec.type=="movie") "movies" else "shows","discovery_collection",
        {it.title},{it.categoryLabel},{it.posterImage},onBack,onItem,emptyMessage="Nothing in this collection yet. Your library is still available.")
}
