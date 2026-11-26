package com.atlas.atlastv.data.catalog

import com.atlas.atlastv.core.network.*
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.playlist.filter.EnglishCatalogPolicy
import com.atlas.atlastv.ui.catalog.cleanProviderTitle
import com.atlas.atlastv.ui.catalog.displayTitle
import com.atlas.atlastv.ui.series.providerDetailsKey
import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.*
import com.atlas.atlastv.xtream.parser.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class ProviderTitle(val id: Int, val title: String)

/** Upgrade existing year-only labels from their provider, without a catalog refresh or guessing names. */
class CatalogTitleRepair(
    private val candidates: suspend () -> List<SafeCatalogItemEntity>,
    private val save: suspend (SafeCatalogItemEntity, String) -> Boolean,
    private val download: suspend (XtreamCredentials, String, String) -> List<ProviderTitle>?,
) {
    suspend fun run(currentAccount: () -> XtreamCredentials?, onChanged: suspend () -> Unit = {}): Int {
        val account = currentAccount() ?: return 0
        val owner = providerDetailsKey(account)
        val groups = candidates().filter { displayTitle(it.title).isBlank() && it.categoryId != null }
            .groupBy { it.contentType to it.categoryId!! }
        var changed = 0
        var published = 0
        var failures = 0
        for ((group, items) in groups) {
            coroutineContext.ensureActive()
            if (currentAccount()?.let(::providerDetailsKey) != owner) return changed
            val titles = try { download(account, group.first, group.second) }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            if (currentAccount()?.let(::providerDetailsKey) != owner) return changed
            if (titles == null) {
                if (++failures >= 3) break
                continue
            }
            failures = 0
            val originals = titles.orEmpty().filter { it.id > 0 }.groupBy { it.id }
            for (row in items) {
                val raw = originals[row.itemId]?.singleOrNull()?.title ?: continue
                val corrected = recoveredCatalogTitle(row, raw) ?: continue
                if (currentAccount()?.let(::providerDetailsKey) != owner) return changed
                if (save(row, corrected)) changed++
            }
            // Publish the prioritized saved-title repair promptly, then one final batch refresh.
            if (published == 0 && changed > 0) { onChanged(); published = changed }
            delay(250)
        }
        if (changed > published) onChanged()
        return changed
    }

    companion object {
        fun create(dao: CatalogTitleRepairDao): CatalogTitleRepair {
            val client = OkHttpTextDownloader()
            val endpoints = XtreamEndpointBuilder()
            val parser = XtreamJsonParser()
            return CatalogTitleRepair(dao::candidates, dao::repair) { account, type, category ->
                val endpoint = if (type == SafeCatalogContentTypes.Series) XtreamEndpoint.Series else XtreamEndpoint.VodStreams
                val body = client.download(endpoints.build(account, endpoint, categoryId = category)) as? TextDownloadResult.Success
                if (body == null) null else if (type == SafeCatalogContentTypes.Series) {
                    (parser.parseSeries(body.text) as? XtreamParseResult.Success)?.value?.mapNotNull { value ->
                        value.seriesId?.let { ProviderTitle(it, value.name.orEmpty()) }
                    }
                } else {
                    (parser.parseVodStreams(body.text) as? XtreamParseResult.Success)?.value?.mapNotNull { value ->
                        value.streamId?.let { ProviderTitle(it, value.name.orEmpty()) }
                    }
                }
            }
        }
    }
}

internal fun recoveredCatalogTitle(row: SafeCatalogItemEntity, raw: String): String? {
    if (displayTitle(row.title).isNotBlank() || raw.length !in 1..240 || raw.contains("://") ||
        EnglishCatalogPolicy.excludesTitle(raw)) return null
    // The original provider name is retained in the search aliases; reject reused/unrelated IDs.
    val original = raw.normalizedCatalogText().trim()
    if (original.isEmpty() || !row.normalizedTitle.trim().endsWith(original)) return null
    return cleanProviderTitle(raw).cleanTitle.takeIf { displayTitle(it).isNotBlank() }
}
