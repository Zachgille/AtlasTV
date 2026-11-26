package com.atlas.atlastv.data.catalog

import com.atlas.atlastv.data.db.SafeCatalogContentTypes
import com.atlas.atlastv.data.db.SafeCatalogItemEntity
import com.atlas.atlastv.data.db.SafeCatalogRepository
import com.atlas.atlastv.data.db.CatalogRefreshWriteResult
import com.atlas.atlastv.data.db.normalizedCatalogText
import com.atlas.atlastv.playlist.filter.PlaylistCategoryFilter
import com.atlas.atlastv.playlist.parser.PlaylistItem
import com.atlas.atlastv.ui.catalog.cleanProviderCategory
import com.atlas.atlastv.ui.catalog.cleanProviderTitle
import com.atlas.atlastv.ui.catalog.searchableTitleText
import com.atlas.atlastv.ui.media.AtlasImageState
import com.atlas.atlastv.ui.media.AtlasProviderImageIndex
import com.atlas.atlastv.ui.media.atlasImageRefFromRaw
import com.atlas.atlastv.xtream.model.XtreamCategory
import com.atlas.atlastv.xtream.model.XtreamSeries
import com.atlas.atlastv.xtream.model.XtreamVodStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class XtreamCatalogImporter(
    private val repository: SafeCatalogRepository,
    private val categoryFilter: PlaylistCategoryFilter = PlaylistCategoryFilter(),
    private val providerImageIndex: AtlasProviderImageIndex? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun importMoviesAndSeries(
        vodCategories: List<XtreamCategory>,
        vodStreams: List<XtreamVodStream>,
        seriesCategories: List<XtreamCategory>,
        series: List<XtreamSeries>,
        baseTimings: CatalogSyncTimings = CatalogSyncTimings(),
        syncStartedAtMs: Long? = null,
    ): CatalogImportResult {
        val startedAt = syncStartedAtMs ?: clockMs()
        val importStartedAt = clockMs()
        val importContext = coroutineContext
        importContext.ensureActive()
        val vodCategoryNames = vodCategories.associate { it.categoryId to it.categoryName.safeCategoryName() }
        val seriesCategoryNames = seriesCategories.associate { it.categoryId to it.categoryName.safeCategoryName() }
        val providerImageEntries = mutableListOf<Pair<String?, String?>>()
        val excludedCategoryNames = (vodCategories + seriesCategories)
            .map { it.categoryName.safeCategoryName() }
            .filter(::isExcludedCategory)
            .distinctBy { it.lowercase() }
        val excludedCategoryKeys = excludedCategoryNames.map { it.categoryKey() }.toSet()
        var excludedItems = 0

        val movieEntities = vodStreams.asSequence()
            .mapIndexedNotNull { index, stream ->
                if (index % 128 == 0) importContext.ensureActive()
                val streamId = stream.streamId?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
                val categoryName = vodCategoryNames[stream.categoryId].safeCategoryName()
                if (categoryName.categoryKey() in excludedCategoryKeys || com.atlas.atlastv.playlist.filter.EnglishCatalogPolicy.excludesTitle(stream.name.orEmpty())) {
                    excludedItems++
                    return@mapIndexedNotNull null
                }
                stream.toEntity(
                    streamId = streamId,
                    categoryName = categoryName,
                    providerOrder = stream.num ?: index,
                    importedAtMs = startedAt,
                    providerImageEntries = providerImageEntries,
                )
            }
            .toList()

        val seriesEntities = series.asSequence()
            .mapIndexedNotNull { index, item ->
                if (index % 128 == 0) importContext.ensureActive()
                val seriesId = item.seriesId?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
                val categoryName = seriesCategoryNames[item.categoryId].safeCategoryName()
                if (categoryName.categoryKey() in excludedCategoryKeys || com.atlas.atlastv.playlist.filter.EnglishCatalogPolicy.excludesTitle(item.name.orEmpty())) {
                    excludedItems++
                    return@mapIndexedNotNull null
                }
                item.toEntity(
                    seriesId = seriesId,
                    categoryName = categoryName,
                    providerOrder = item.num ?: index,
                    importedAtMs = startedAt,
                    providerImageEntries = providerImageEntries,
                )
            }
            .toList()
        val filteringCompletedAt = clockMs()
        val movieById = movieEntities.associateBy { it.itemId }
        val showById = seriesEntities.associateBy { it.itemId }
        val discovery = vodStreams.mapNotNull { raw -> movieById[raw.streamId]?.let { kept ->
            com.atlas.atlastv.data.discovery.DiscoveryMetadata.create(kept.contentType, kept.itemId, raw.name.orEmpty(), kept.updatedAtMs, importStartedAt,
                rating = raw.rating, fiveBased = raw.rating5Based, added = raw.added)
        } } + series.mapNotNull { raw -> showById[raw.seriesId]?.let { kept ->
            com.atlas.atlastv.data.discovery.DiscoveryMetadata.create(kept.contentType, kept.itemId, raw.name.orEmpty(), kept.updatedAtMs, importStartedAt,
                rating = raw.rating, fiveBased = raw.rating5Based, release = raw.releaseDate, changed = raw.lastModified, genre = raw.genre)
        } }
        importContext.ensureActive()
        val replacement = repository.replaceLibraryFromRefresh(movieEntities, seriesEntities, discovery)
        if (replacement == CatalogRefreshWriteResult.RefusedEmptyCatalog) {
            val completedAt = clockMs()
            return CatalogImportResult(
                status = CatalogSyncStatus.Failure,
                startedAtMs = startedAt,
                completedAtMs = completedAt,
                moviesImported = 0,
                seriesImported = 0,
                excludedCategories = excludedCategoryNames.size,
                excludedItems = excludedItems,
                errorCategory = replacement.name,
                timings = baseTimings.copy(
                    filteringDurationMs = (filteringCompletedAt - importStartedAt).coerceAtLeast(0L),
                    roomWriteDurationMs = (completedAt - filteringCompletedAt).coerceAtLeast(0L),
                ).withTotal(completedAt - startedAt),
            )
        }
        // Do not register artwork for refused data. Optional artwork cannot fail a committed catalog.
        try {
            providerImageIndex?.registerAllAsync(providerImageEntries)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { }
        val moviesImported = movieEntities.size
        val seriesImported = seriesEntities.size

        val completedAt = clockMs()
        val filteringDurationMs = (filteringCompletedAt - importStartedAt).coerceAtLeast(0L)
        val roomWriteDurationMs = (completedAt - filteringCompletedAt).coerceAtLeast(0L)
        return CatalogImportResult(
            status = CatalogSyncStatus.Success,
            startedAtMs = startedAt,
            completedAtMs = completedAt,
            moviesImported = moviesImported,
            seriesImported = seriesImported,
            excludedCategories = excludedCategoryNames.size,
            excludedItems = excludedItems,
            timings = baseTimings.copy(
                filteringDurationMs = filteringDurationMs,
                roomWriteDurationMs = roomWriteDurationMs,
            ).withTotal(completedAt - startedAt),
        )
    }

    private fun XtreamVodStream.toEntity(
        streamId: Int,
        categoryName: String,
        providerOrder: Int,
        importedAtMs: Long,
        providerImageEntries: MutableList<Pair<String?, String?>>,
    ): SafeCatalogItemEntity {
        val rawTitle = name.safeCatalogText("Movie $streamId")
        val title = cleanProviderTitle(rawTitle).cleanTitle.safeCatalogText("Movie $streamId")
        val displayCategory = cleanProviderCategory(categoryName).safeCatalogText("Uncategorized")
        val posterImage = atlasImageRefFromRaw(streamIcon, rememberProviderAddress = providerImageIndex == null)
        providerImageEntries.addProviderImage(posterImage.state, posterImage.providerImageToken, streamIcon)
        return SafeCatalogItemEntity(
            contentType = SafeCatalogContentTypes.Movie,
            itemId = streamId,
            title = title,
            normalizedTitle = searchableTitleText(rawTitle).normalizedCatalogText(),
            categoryId = categoryId.safeOptionalText(),
            categoryName = displayCategory,
            providerOrder = providerOrder,
            addedAtMs = added?.trim()?.toLongOrNull(),
            containerExtension = containerExtension.safeExtension(DefaultMovieExtension),
            posterState = posterImage.state.name,
            posterSafeUri = posterImage.safeUri,
            posterProviderToken = posterImage.providerImageToken,
            posterRedactedSource = posterImage.redactedSource,
            importedAtMs = importedAtMs,
            updatedAtMs = importedAtMs,
        )
    }

    private fun XtreamSeries.toEntity(
        seriesId: Int,
        categoryName: String,
        providerOrder: Int,
        importedAtMs: Long,
        providerImageEntries: MutableList<Pair<String?, String?>>,
    ): SafeCatalogItemEntity {
        val rawTitle = name.safeCatalogText("Series $seriesId")
        val title = cleanProviderTitle(rawTitle).cleanTitle.safeCatalogText("Series $seriesId")
        val displayCategory = cleanProviderCategory(categoryName).safeCatalogText("Uncategorized")
        val posterImage = atlasImageRefFromRaw(cover, rememberProviderAddress = providerImageIndex == null)
        providerImageEntries.addProviderImage(posterImage.state, posterImage.providerImageToken, cover)
        return SafeCatalogItemEntity(
            contentType = SafeCatalogContentTypes.Series,
            itemId = seriesId,
            title = title,
            normalizedTitle = searchableTitleText(rawTitle).normalizedCatalogText(),
            categoryId = categoryId.safeOptionalText(),
            categoryName = displayCategory,
            providerOrder = providerOrder,
            addedAtMs = lastModified?.trim()?.toLongOrNull(),
            containerExtension = null,
            posterState = posterImage.state.name,
            posterSafeUri = posterImage.safeUri,
            posterProviderToken = posterImage.providerImageToken,
            posterRedactedSource = posterImage.redactedSource,
            importedAtMs = importedAtMs,
            updatedAtMs = importedAtMs,
        )
    }

    private fun isExcludedCategory(categoryName: String): Boolean {
        val result = categoryFilter.filter(
            listOf(
                PlaylistItem(
                    tvgId = null,
                    tvgName = null,
                    tvgLogo = null,
                    groupTitle = categoryName,
                    displayName = categoryName,
                    streamUrl = "",
                    rawExtinf = "",
                ),
            ),
        )
        return result.excludedCount > 0
    }

    private fun String.categoryKey(): String =
        trim().lowercase()

    private fun String?.safeCategoryName(): String =
        safeCatalogText("Uncategorized")

    private fun String?.safeOptionalText(): String? =
        this?.safeCatalogText("")?.takeIf { it.isNotBlank() }

    private fun String?.safeExtension(defaultValue: String): String =
        this?.trim()
            ?.trimStart('.')
            ?.takeIf { it.isNotBlank() && !it.contains("/") && !it.contains(":") }
            ?.take(MaxExtensionLength)
            ?: defaultValue

    private fun String?.safeCatalogText(defaultValue: String): String {
        val trimmed = this?.trim().orEmpty()
        return when {
            trimmed.isBlank() -> defaultValue
            trimmed.contains("://") -> defaultValue
            trimmed.contains("/live/", ignoreCase = true) -> defaultValue
            trimmed.contains("/movie/", ignoreCase = true) -> defaultValue
            trimmed.contains("/series/", ignoreCase = true) -> defaultValue
            else -> trimmed.take(MaxSafeCatalogTextLength)
        }
    }

    private fun MutableList<Pair<String?, String?>>.addProviderImage(
        state: AtlasImageState,
        token: String?,
        rawUrl: String?,
    ) {
        if (state == AtlasImageState.ProviderImage) {
            add(token to rawUrl)
        }
    }

    private companion object {
        private const val DefaultMovieExtension = "mp4"
        private const val MaxExtensionLength = 12
        private const val MaxSafeCatalogTextLength = 240
    }
}
