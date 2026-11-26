package com.atlas.atlastv.ui.movies

import android.content.Context
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.xtream.model.XtreamCredentials
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong

data class SavedMovieDetails(val providerKey: String, val streamId: Int, val requestedAtMs: Long, val savedAtMs: Long,
    val catalogAtMs: Long?, val metadata: MovieMetadata)
interface MovieDetailsStore {
    suspend fun read(key: String, id: Int): SavedMovieDetails?
    suspend fun save(value: SavedMovieDetails)
}
class RoomMovieDetailsStore(private val dao: MovieDetailsCacheDao, private val clockMs: () -> Long = { System.currentTimeMillis() }) : MovieDetailsStore {
    override suspend fun read(key: String, id: Int): SavedMovieDetails? {
        val row = dao.entry(key, id) ?: return null
        val metadata = MovieDetailsCacheCodec.decode(row.payload)
        if (metadata == null || row.payload.toByteArray(Charsets.UTF_8).size != row.payloadBytes) {
            dao.deleteVersion(key, id, row.requestedAtMs); return null
        }
        try { dao.touch(key, id, row.requestedAtMs, clockMs()) }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Optional LRU bookkeeping. */ }
        return SavedMovieDetails(key, id, row.requestedAtMs, row.savedAtMs, row.catalogAtMs, metadata)
    }
    override suspend fun save(value: SavedMovieDetails) {
        if (value.streamId <= 0) return
        val payload = MovieDetailsCacheCodec.encode(value.metadata) ?: return
        dao.saveBounded(MovieDetailsCacheEntity(value.providerKey, value.streamId, value.requestedAtMs, value.savedAtMs,
            value.catalogAtMs, clockMs(), payload.toByteArray(Charsets.UTF_8).size, payload), 64, 4L * 1024 * 1024)
    }
}
class MovieDetailsRepository(private val store: MovieDetailsStore,
    private val download: suspend (XtreamCredentials, Int) -> MovieMetadata?,
    private val catalogStamp: suspend () -> Long? = { null }, private val clockMs: () -> Long = { System.currentTimeMillis() }) {
    private val requests = AtomicLong()
    suspend fun saved(credentials: XtreamCredentials, id: Int): SavedMovieDetails? = try { store.read(providerMovieDetailsKey(credentials), id) }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    private suspend fun stamp(): Long? = try { catalogStamp() }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    suspend fun stale(value: SavedMovieDetails): Boolean {
        val age = clockMs() - value.savedAtMs
        return age < 0 || age >= FreshMs || value.catalogAtMs != stamp()
    }
    suspend fun refresh(credentials: XtreamCredentials, id: Int): MovieMetadata? {
        val request = requests.updateAndGet { maxOf(it + 1, clockMs()) }
        val catalogAt = stamp()
        val previous = saved(credentials, id)
        val metadata = download(credentials, id)?.let(MovieDetailsCacheCodec::safe)?.takeIf { it.hasDetails() } ?: return null
        val result = metadata.withLastGood(previous?.metadata)
        try { store.save(SavedMovieDetails(providerMovieDetailsKey(credentials), id, request, clockMs(), catalogAt, result)) }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Cache failure must not discard online information. */ }
        return result
    }
    companion object {
        const val FreshMs = 6L * 60 * 60 * 1000
        fun create(context: Context, loader: MovieInfoLoader = MovieInfoLoader(registerArtwork = {
            com.atlas.atlastv.ui.media.AtlasProviderImageIndex.create(context).registerAllAsync(it)
        })): MovieDetailsRepository {
            val db = AtlasDatabaseProvider.database(context)
            return MovieDetailsRepository(RoomMovieDetailsStore(db.movieDetailsCacheDao()), loader::load,
                { db.safeCatalogDao().newestUpdatedAtMs(SafeCatalogContentTypes.Movie) })
        }
    }
}
fun providerMovieDetailsKey(credentials: XtreamCredentials): String = providerDetailsCacheKey(credentials, "AtlasTV Movie details v1")
