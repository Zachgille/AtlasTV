package com.atlas.atlastv.data.db

import androidx.room.Entity
import androidx.room.Index
import com.atlas.atlastv.ui.media.AtlasImageRef
import com.atlas.atlastv.ui.media.AtlasImageState
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.series.SeriesItem
import com.atlas.atlastv.ui.catalog.cleanProviderCategory
import com.atlas.atlastv.ui.catalog.cleanProviderTitle

@Entity(
    tableName = "safe_catalog_items",
    primaryKeys = ["contentType", "itemId"],
    indices = [
        Index(value = ["contentType", "providerOrder"]),
        Index(value = ["contentType", "normalizedTitle"]),
        Index(value = ["contentType", "categoryName"]),
        Index(value = ["contentType", "categoryId"]),
        Index(value = ["contentType", "addedAtMs"]),
    ],
)
data class SafeCatalogItemEntity(
    val contentType: String,
    val itemId: Int,
    val title: String,
    val normalizedTitle: String = title.normalizedCatalogText(),
    val categoryId: String? = null,
    val categoryName: String,
    val providerOrder: Int,
    val addedAtMs: Long? = null,
    val containerExtension: String?,
    val posterState: String,
    val posterSafeUri: String?,
    val posterProviderToken: String?,
    val posterRedactedSource: String?,
    val importedAtMs: Long = 0L,
    val updatedAtMs: Long,
)

object SafeCatalogContentTypes {
    const val Movie = "movie"
    const val Series = "series"
}

data class CatalogLanguageItem(val contentType: String, val itemId: Int, val title: String)

fun MovieItem.toSafeCatalogEntity(providerOrder: Int, updatedAtMs: Long): SafeCatalogItemEntity =
    SafeCatalogItemEntity(
        contentType = SafeCatalogContentTypes.Movie,
        itemId = streamId,
        title = title.safeCatalogText("Movie $streamId"),
        categoryId = null,
        categoryName = categoryName.safeCatalogText("Uncategorized"),
        providerOrder = providerOrder,
        addedAtMs = null,
        containerExtension = containerExtension.trim().trimStart('.').takeIf { it.isNotBlank() },
        posterState = posterImage.state.name,
        posterSafeUri = posterImage.safeUri,
        posterProviderToken = posterImage.providerImageToken,
        posterRedactedSource = posterImage.redactedSource,
        importedAtMs = updatedAtMs,
        updatedAtMs = updatedAtMs,
    )

fun SeriesItem.toSafeCatalogEntity(providerOrder: Int, updatedAtMs: Long): SafeCatalogItemEntity =
    SafeCatalogItemEntity(
        contentType = SafeCatalogContentTypes.Series,
        itemId = seriesId,
        title = name.safeCatalogText("Series $seriesId"),
        categoryId = null,
        categoryName = categoryName.safeCatalogText("Uncategorized"),
        providerOrder = providerOrder,
        addedAtMs = null,
        containerExtension = null,
        posterState = posterImage.state.name,
        posterSafeUri = posterImage.safeUri,
        posterProviderToken = posterImage.providerImageToken,
        posterRedactedSource = posterImage.redactedSource,
        importedAtMs = updatedAtMs,
        updatedAtMs = updatedAtMs,
    )

fun SafeCatalogItemEntity.toMovieItem(redactedStreamUrl: String): MovieItem =
    MovieItem(
        title = cleanProviderTitle(title).cleanTitle,
        categoryName = cleanProviderCategory(categoryName),
        streamId = itemId,
        containerExtension = containerExtension?.takeIf { it.isNotBlank() } ?: "mp4",
        redactedStreamUrl = redactedStreamUrl,
        posterImage = toImageRef(),
    )

fun SafeCatalogItemEntity.toSeriesItem(): SeriesItem =
    SeriesItem(
        name = cleanProviderTitle(title).cleanTitle,
        categoryName = cleanProviderCategory(categoryName),
        seriesId = itemId,
        posterImage = toImageRef(),
    )

private fun SafeCatalogItemEntity.toImageRef(): AtlasImageRef =
    AtlasImageRef(
        state = runCatching { AtlasImageState.valueOf(posterState) }.getOrDefault(AtlasImageState.Missing),
        safeUri = posterSafeUri,
        providerImageToken = posterProviderToken,
        redactedSource = posterRedactedSource,
    )

private fun String.safeCatalogText(defaultValue: String): String {
    val trimmed = trim()
    return when {
        trimmed.isBlank() -> defaultValue
        trimmed.contains("://") -> defaultValue
        trimmed.contains("/live/", ignoreCase = true) -> defaultValue
        trimmed.contains("/movie/", ignoreCase = true) -> defaultValue
        trimmed.contains("/series/", ignoreCase = true) -> defaultValue
        else -> trimmed.take(MaxSafeCatalogTextLength)
    }
}

fun String.normalizedCatalogText(): String =
    trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .take(MaxSafeCatalogTextLength)

private const val MaxSafeCatalogTextLength = 240
