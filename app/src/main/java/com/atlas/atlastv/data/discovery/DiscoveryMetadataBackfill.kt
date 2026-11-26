package com.atlas.atlastv.data.discovery

import com.atlas.atlastv.core.network.*
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.ui.catalog.cleanProviderTitle
import com.atlas.atlastv.ui.series.providerDetailsKey
import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.*
import com.atlas.atlastv.xtream.parser.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class DiscoveryProviderItem(val id: Int, val title: String, val rating: String?, val fiveBased: String?,
    val release: String? = null, val added: String? = null, val changed: String? = null, val genre: String? = null)

/** Upgrade-only metadata fill. Never imports/removes catalog, artwork, settings or personal records. */
class DiscoveryMetadataBackfill(private val dao: SafeCatalogDao,
    private val download: suspend (XtreamCredentials, String, String) -> List<DiscoveryProviderItem>?,
    private val clockMs: () -> Long = { System.currentTimeMillis() }) {
    suspend fun run(account: XtreamCredentials, currentAccount: () -> XtreamCredentials?): Int {
        val key = providerDetailsKey(account)
        var changed = 0
        for (type in listOf(SafeCatalogContentTypes.Movie, SafeCatalogContentTypes.Series)) {
            val categories = dao.discoveryCategories(type)
            if (categories.size > 500) continue
            for (category in categories) {
                coroutineContext.ensureActive()
                if (currentAccount()?.let(::providerDetailsKey) != key) return changed
                val prior = dao.discoveryBackfill(type, category.id)
                val now = clockMs()
                if (prior != null && prior.catalogAtMs == category.stamp && (prior.providerKey == key || prior.providerKey == "catalog-import") &&
                    (prior.complete || now < prior.attemptedAtMs + 6 * 60 * 60 * 1000L)) continue
                val attempt = DiscoveryBackfillEntity(type, category.id, category.stamp, key, now, false)
                dao.saveDiscoveryBackfill(attempt)
                val raw = try { download(account, type, category.id) }
                    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                coroutineContext.ensureActive()
                if (currentAccount()?.let(::providerDetailsKey) != key) return changed
                if (raw == null || raw.isEmpty() || raw.size > 50_000) continue
                val values = raw.filter { it.id > 0 && !com.atlas.atlastv.playlist.filter.EnglishCatalogPolicy.excludesTitle(it.title) }.distinctBy { it.id }
                val retained = values.map { it.id }.chunked(200).flatMap { dao.discoveryRetainedIds(type, category.id, it) }.associateBy { it.itemId }
                val metadata = values.mapNotNull { source -> retained[source.id]?.takeIf {
                    it.updatedAtMs == category.stamp && cleanProviderTitle(source.title).cleanTitle == it.title
                }?.let { item -> DiscoveryMetadata.create(type, item.itemId, source.title, item.updatedAtMs, now,
                    source.rating, source.fiveBased, source.release, source.added, source.changed, source.genre) } }
                if (metadata.isEmpty()) continue
                dao.commitDiscovery(type, category.id, category.stamp, metadata, attempt.copy(complete = true))
                changed += metadata.size
            }
        }
        return changed
    }
    companion object {
        fun create(dao: SafeCatalogDao): DiscoveryMetadataBackfill {
            val downloader = OkHttpTextDownloader()
            val endpoints = XtreamEndpointBuilder()
            val parser = XtreamJsonParser()
            return DiscoveryMetadataBackfill(dao, download = { account, type, category ->
                val result = downloader.download(endpoints.build(account, if (type == SafeCatalogContentTypes.Movie) XtreamEndpoint.VodStreams else XtreamEndpoint.Series, categoryId = category))
                if (result !is TextDownloadResult.Success) null
                else if (type == SafeCatalogContentTypes.Movie) {
                    (parser.parseVodStreams(result.text) as? XtreamParseResult.Success)?.value?.mapNotNull { row -> row.streamId?.let {
                        DiscoveryProviderItem(it, row.name.orEmpty(), row.rating, row.rating5Based, added = row.added)
                    } }
                } else {
                    (parser.parseSeries(result.text) as? XtreamParseResult.Success)?.value?.mapNotNull { row -> row.seriesId?.let {
                        DiscoveryProviderItem(it, row.name.orEmpty(), row.rating, row.rating5Based, row.releaseDate, changed = row.lastModified, genre = row.genre)
                    } }
                }
            })
        }
    }
}
