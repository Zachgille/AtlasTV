package com.atlas.atlastv.xtream.network

enum class XtreamLoadStage(val label: String) {
    AccountInfo("Loading account info"),
    LiveCategories("Loading live categories"),
    LiveStreams("Loading live streams"),
    VodCategories("Loading VOD categories"),
    VodStreams("Loading VOD streams"),
    SeriesCategories("Loading series categories"),
    Series("Loading series list"),
    ImportingCatalog("Saving catalog"),
}
