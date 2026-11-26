package com.atlas.atlastv.ui.series

import com.atlas.atlastv.data.db.safeProgressText
import com.atlas.atlastv.ui.media.*
import com.google.gson.Gson

/** Explicit DTOs exclude stream addresses, provider URLs and credentials from persisted details. */
object ShowDetailsCacheCodec {
    const val MaxEntryBytes = 8 * 1024 * 1024
    private val gson = Gson()
    private data class Image(val token: String? = null, val local: String? = null)
    private data class Episode(val id: Int, val season: Int?, val number: Int?, val title: String,
        val extension: String, val duration: Long?, val image: Image)
    private data class Payload(val version: Int = 1, val episodes: List<Episode>, val synopsis: String?,
        val genre: String?, val year: String?, val backdrop: Image, val poster: Image)
    private data class MetadataPayload(val version: Int = 1, val synopsis: String?, val genre: String?,
        val year: String?, val backdrop: Image, val poster: Image)
    private fun safeLocal(value: String) = value.startsWith("fixture://poster/") || value.startsWith("asset://poster/") || value.startsWith("android.resource://")
    private fun extension(value: String) = value.trim().trimStart('.').takeIf { it.matches(Regex("[a-zA-Z0-9]{1,12}")) } ?: "mp4"
    private fun title(value: String) = cleanShowDescription(value, 160)?.safeProgressText("Episode") ?: "Episode"
    private fun image(ref: AtlasImageRef) = Image(ref.providerImageToken?.takeIf(::isProviderImageToken),
        ref.safeUri?.takeIf(::safeLocal))
    private fun image(value: Image): AtlasImageRef = value.token?.takeIf(::isProviderImageToken)?.let { atlasImageRefFromRaw(it, false) }
        ?: value.local?.takeIf(::safeLocal)?.let { atlasImageRefFromRaw(it, false) } ?: AtlasImageRef.Missing

    fun encode(result: SeriesEpisodeLoadResult.Success): String? {
        if (result.episodes.isEmpty() || result.episodes.any { it.episodeId <= 0 } || result.episodes.map { it.episodeId }.distinct().size != result.episodes.size) return null
        val metadata = result.metadata
        val payload = Payload(episodes = result.episodes.map { Episode(it.episodeId, it.seasonNumber, it.episodeNumber,
            title(it.title), extension(it.containerExtension),
            com.atlas.atlastv.core.media.MediaRuntime.validMillis(it.durationMs), image(it.thumbnail)) },
            synopsis = cleanShowDescription(metadata.synopsis), genre = cleanShowDescription(metadata.genre, 100),
            year = showReleaseYear(metadata.releaseYear), backdrop = image(metadata.backdrop), poster = image(metadata.poster))
        val json = gson.toJson(payload)
        return json.takeIf { it.toByteArray(Charsets.UTF_8).size <= MaxEntryBytes }
    }
    fun decode(json: String): SeriesEpisodeLoadResult.Success? = try {
        if (json.toByteArray(Charsets.UTF_8).size > MaxEntryBytes) null else {
            val value = gson.fromJson(json, Payload::class.java)
            if (value.version != 1 || value.episodes.isEmpty() || value.episodes.any { it.id <= 0 } || value.episodes.map { it.id }.distinct().size != value.episodes.size) null
            else SeriesEpisodeLoadResult.Success(value.episodes.map { SeriesEpisodeItem(title(it.title),
                it.season, it.number, it.id, extension(it.extension), "",
                com.atlas.atlastv.core.media.MediaRuntime.validMillis(it.duration), image(it.image)) },
                SeriesShowMetadata(cleanShowDescription(value.synopsis), cleanShowDescription(value.genre, 100),
                    showReleaseYear(value.year), image(value.backdrop), image(value.poster)), fromSaved = true)
        }
    } catch (_: Exception) { null }

    /** Home previews skip the episodes field instead of allocating a second complete episode list. */
    fun decodeMetadata(json: String): SeriesShowMetadata? = try {
        if (json.toByteArray(Charsets.UTF_8).size > MaxEntryBytes) null else {
            val value = gson.fromJson(json, MetadataPayload::class.java)
            if (value.version != 1) null else SeriesShowMetadata(cleanShowDescription(value.synopsis),
                cleanShowDescription(value.genre, 100), showReleaseYear(value.year), image(value.backdrop), image(value.poster))
        }
    } catch (_: Exception) { null }
}
