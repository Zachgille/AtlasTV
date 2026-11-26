package com.atlas.atlastv.ui.home

import android.content.Context
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.ui.movies.*
import com.atlas.atlastv.ui.series.*
import com.atlas.atlastv.xtream.model.XtreamCredentials
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong

data class SavedHomeHero(val providerKey: String, val metadataKey: String, val requestedAtMs: Long,
    val savedAtMs: Long, val catalogAtMs: Long?, val metadata: HomeHeroMetadata)
interface HomeHeroStore {
    suspend fun read(key: String, id: String): SavedHomeHero?
    suspend fun save(value: SavedHomeHero)
}
class RoomHomeHeroStore(private val dao: HomeHeroCacheDao, private val clockMs: () -> Long = { System.currentTimeMillis() }) : HomeHeroStore {
    override suspend fun read(key: String, id: String): SavedHomeHero? {
        val row = dao.entry(key, id) ?: return null
        val value = HomeHeroCacheCodec.decode(row.payload)
        if (value == null || row.payload.toByteArray(Charsets.UTF_8).size != row.payloadBytes) {
            dao.deleteVersion(key, id, row.requestedAtMs); return null
        }
        try { dao.touch(key, id, row.requestedAtMs, clockMs()) }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Optional access bookkeeping. */ }
        return SavedHomeHero(key, id, row.requestedAtMs, row.savedAtMs, row.catalogAtMs, value)
    }
    override suspend fun save(value: SavedHomeHero) {
        if (!value.metadataKey.matches(Regex("(movie|show)-[1-9][0-9]*"))) return
        val json = HomeHeroCacheCodec.encode(value.metadata) ?: return
        dao.saveBounded(HomeHeroCacheEntity(value.providerKey, value.metadataKey, value.requestedAtMs, value.savedAtMs,
            value.catalogAtMs, clockMs(), json.toByteArray(Charsets.UTF_8).size, json), 64, 4L * 1024 * 1024)
    }
}

class HomeHeroRepository(private val store: HomeHeroStore,
    private val download: suspend (XtreamCredentials, HomeCard) -> HomeHeroMetadata?,
    private val catalogStamp: suspend (HomeCard) -> Long? = { null },
    private val detailFallback: suspend (XtreamCredentials, HomeCard) -> SavedHomeHero? = { _, _ -> null },
    private val clockMs: () -> Long = { System.currentTimeMillis() }) {
    private val requests = AtomicLong()
    private suspend fun <T> optional(block: suspend () -> T): T? = try { block() }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    /** Shelf lookup is read-only: no metadata request, catalog scan or preview-cache write. */
    suspend fun savedPosters(account: XtreamCredentials, card: HomeCard): List<com.atlas.atlastv.ui.media.AtlasImageRef> {
        val id = card.heroCacheIdentity() ?: return emptyList()
        val key = providerHomeHeroKey(account)
        return listOfNotNull(optional { store.read(key, id) }, optional { detailFallback(account, card) })
            .filter { it.providerKey == key && it.metadataKey == id }
            .sortedByDescending { it.savedAtMs }
            .map { HomeHeroCacheCodec.safe(it.metadata).poster }
            .filter { it.state == com.atlas.atlastv.ui.media.AtlasImageState.ProviderImage &&
                com.atlas.atlastv.ui.media.isProviderImageToken(it.providerImageToken.orEmpty()) }
            .distinct().take(2)
    }
    suspend fun saved(account: XtreamCredentials, card: HomeCard): SavedHomeHero? {
        val id = card.heroCacheIdentity() ?: return null
        val key = providerHomeHeroKey(account)
        fun valid(value: SavedHomeHero?) = value?.takeIf { it.providerKey == key && it.metadataKey == id && it.metadata.hasDetails() }
        val own = valid(optional { store.read(key, id) })
        val detail = valid(optional { detailFallback(account, card) })
        val best = listOfNotNull(own, detail).maxByOrNull { it.savedAtMs } ?: return null
        val result = best.copy(metadata = best.metadata.withLastGood(if (best === detail) own?.metadata else detail?.metadata))
        if (result != own) optional { store.save(result) }
        return result
    }
    suspend fun stale(value: SavedHomeHero, card: HomeCard): Boolean {
        val age = clockMs() - value.savedAtMs
        return value.metadataKey != card.heroCacheIdentity() || age < 0 || age >= FreshMs || value.catalogAtMs != optional { catalogStamp(card) }
    }
    suspend fun refresh(account: XtreamCredentials, card: HomeCard): HomeHeroMetadata? {
        val id = card.heroCacheIdentity() ?: return null
        val request = requests.updateAndGet { maxOf(it + 1, clockMs()) }
        val stamp = optional { catalogStamp(card) }
        val previous = saved(account, card)
        val metadata = download(account, card)?.let(HomeHeroCacheCodec::safe)?.takeIf { it.hasDetails() } ?: return null
        val result = metadata.withLastGood(previous?.metadata)
        optional { store.save(SavedHomeHero(providerHomeHeroKey(account), id, request, clockMs(), stamp, result)) }
        return result
    }
    companion object {
        const val FreshMs = 6L * 60 * 60 * 1000
        fun create(context: Context, loader: HomeHeroMetadataLoader, db: AtlasDatabase = AtlasDatabaseProvider.database(context)): HomeHeroRepository {
            val movies = RoomMovieDetailsStore(db.movieDetailsCacheDao())
            return HomeHeroRepository(RoomHomeHeroStore(db.homeHeroCacheDao()), loader::load,
                { db.safeCatalogDao().newestUpdatedAtMs(if (it.contentType == HomeContentType.Movie) SafeCatalogContentTypes.Movie else SafeCatalogContentTypes.Series) },
                { account, card ->
                    val key = providerHomeHeroKey(account)
                    val id = card.heroCacheIdentity()!!
                    if (card.contentType == HomeContentType.Movie) movies.read(providerMovieDetailsKey(account), card.streamId)?.let {
                        SavedHomeHero(key, id, it.requestedAtMs, it.savedAtMs, it.catalogAtMs, it.metadata.asHero())
                    } else db.showDetailsCacheDao().entry(providerDetailsKey(account), card.seriesId ?: card.streamId)?.let { row ->
                        if (row.payload.toByteArray(Charsets.UTF_8).size != row.payloadBytes) null else ShowDetailsCacheCodec.decodeMetadata(row.payload)?.let { m ->
                            try { db.showDetailsCacheDao().touch(row.providerKey, row.seriesId, row.requestedAtMs, System.currentTimeMillis()) }
                            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Optional metadata access. */ }
                            SavedHomeHero(key, id, row.requestedAtMs, row.savedAtMs, row.catalogAtMs,
                                HomeHeroCacheCodec.safe(HomeHeroMetadata(m.synopsis, m.genre, m.releaseYear, backdrop = m.backdrop, poster = m.poster)))
                        }
                    }
                })
        }
    }
}
fun providerHomeHeroKey(account: XtreamCredentials) = providerDetailsCacheKey(account, "AtlasTV Home hero v1")
