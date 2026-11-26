package com.atlas.atlastv.xtream.mapper

data class AtlasCatalogItem(
    val contentType: AtlasContentType,
    val id: String,
    val name: String,
    val categoryId: String?,
    val categoryName: String?,
    val iconUrl: String?,
    val streamId: Int?,
    val seriesId: Int?,
    val containerExtension: String?,
)
