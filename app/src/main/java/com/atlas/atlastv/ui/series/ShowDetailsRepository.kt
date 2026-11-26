package com.atlas.atlastv.ui.series

import android.content.Context
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.xtream.model.XtreamCredentials
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong

data class SavedShowDetails(val providerKey: String, val seriesId: Int, val requestedAtMs: Long, val savedAtMs: Long,
    val catalogAtMs: Long?, val result: SeriesEpisodeLoadResult.Success)
interface ShowDetailsStore {
    suspend fun read(key: String, id: Int): SavedShowDetails?
    suspend fun save(value: SavedShowDetails)
}

class RoomShowDetailsStore(private val dao: ShowDetailsCacheDao, private val clockMs: () -> Long = { System.currentTimeMillis() }) : ShowDetailsStore {
    override suspend fun read(key: String, id: Int): SavedShowDetails? {
        val row = dao.entry(key, id) ?: return null
        val result = ShowDetailsCacheCodec.decode(row.payload)
        if (result == null || row.payload.toByteArray(Charsets.UTF_8).size != row.payloadBytes) {
            dao.deleteVersion(key, id, row.requestedAtMs); return null
        }
        try { dao.touch(key, id, row.requestedAtMs, clockMs()) }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Usage bookkeeping is optional. */ }
        return SavedShowDetails(key, id, row.requestedAtMs, row.savedAtMs, row.catalogAtMs, result)
    }
    override suspend fun save(value: SavedShowDetails) {
        val json = ShowDetailsCacheCodec.encode(value.result) ?: return
        dao.saveBounded(ShowDetailsCacheEntity(value.providerKey, value.seriesId, value.requestedAtMs, value.savedAtMs,
            value.catalogAtMs, clockMs(), json.toByteArray(Charsets.UTF_8).size, json), 32, 64L * 1024 * 1024)
    }
}

/** Account-scoped cache first for details, with explicit refresh and last-good playback lookup fallback. */
class ShowDetailsRepository(private val store: ShowDetailsStore,
    private val download: suspend (XtreamCredentials, Int) -> SeriesEpisodeLoadResult,
    private val catalogStamp: suspend () -> Long? = { null }, private val clockMs: () -> Long = { System.currentTimeMillis() }) {
    private val requests = AtomicLong()
    suspend fun saved(credentials: XtreamCredentials, id: Int): SavedShowDetails? = try { store.read(providerDetailsKey(credentials), id) }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    suspend fun stale(value: SavedShowDetails): Boolean {
        val age = clockMs() - value.savedAtMs
        return age < 0 || age >= FreshMs || value.catalogAtMs != safeCatalogStamp()
    }
    private suspend fun safeCatalogStamp(): Long? = try { catalogStamp() }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    suspend fun refresh(credentials: XtreamCredentials, id: Int): SeriesEpisodeLoadResult {
        val request = requests.updateAndGet { maxOf(it + 1, clockMs()) }
        val stamp = safeCatalogStamp()
        val previous = saved(credentials, id)
        val downloaded = download(credentials, id)
        val result = if (downloaded is SeriesEpisodeLoadResult.Success) downloaded.copy(metadata = downloaded.metadata.withLastGood(previous?.result?.metadata)) else downloaded
        if (result is SeriesEpisodeLoadResult.Success && result.episodes.isNotEmpty()) {
            try { store.save(SavedShowDetails(providerDetailsKey(credentials), id, request, clockMs(), stamp, result)) }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Optional cache cannot block online content. */ }
        }
        return result
    }
    suspend fun forPlayback(credentials: XtreamCredentials, id: Int, forceRefresh: Boolean = false): SeriesEpisodeLoadResult {
        val cached = saved(credentials, id)
        if (cached != null && !forceRefresh && !stale(cached)) return cached.result
        return try {
            when (val result = refresh(credentials, id)) {
                is SeriesEpisodeLoadResult.Success -> result.takeIf { it.episodes.isNotEmpty() } ?: cached?.result ?: result
                is SeriesEpisodeLoadResult.Failure -> cached?.result ?: result
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { cached?.result ?: SeriesEpisodeLoadResult.Failure("Unavailable") }
    }
    companion object {
        const val FreshMs = 6L * 60 * 60 * 1000
        fun create(context: Context, loader: SeriesEpisodeLoader = SeriesEpisodeLoader(registerArtwork = {
            com.atlas.atlastv.ui.media.AtlasProviderImageIndex.create(context).registerAllAsync(it)
        })): ShowDetailsRepository {
            val db = AtlasDatabaseProvider.database(context)
            return ShowDetailsRepository(RoomShowDetailsStore(db.showDetailsCacheDao()), { credentials, id -> loader.loadEpisodes(credentials, id) },
                { db.safeCatalogDao().newestUpdatedAtMs(SafeCatalogContentTypes.Series) })
        }
    }
}

fun providerDetailsKey(credentials: XtreamCredentials): String = providerDetailsCacheKey(credentials, "AtlasTV Show details v1")
