package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.ui.catalog.cleanDetailDescription
import com.atlas.atlastv.ui.catalog.detailReleaseYear
import com.atlas.atlastv.ui.media.*
import com.google.gson.Gson

/** Explicit DTOs keep URLs and account settings outside saved presentation information. */
object MovieDetailsCacheCodec {
    const val MaxEntryBytes = 64 * 1024
    private val gson = Gson()
    private data class Image(val token: String? = null, val local: String? = null)
    private data class Payload(val version: Int = 1, val synopsis: String?, val genre: String?, val year: String?,
        val minutes: Int?, val backdrop: Image, val poster: Image)
    private fun safeLocal(value: String) = value.startsWith("fixture://poster/") || value.startsWith("asset://poster/") || value.startsWith("android.resource://")
    private fun image(ref: AtlasImageRef) = Image(ref.providerImageToken?.takeIf(::isProviderImageToken), ref.safeUri?.takeIf(::safeLocal))
    private fun image(value: Image): AtlasImageRef = value.token?.takeIf(::isProviderImageToken)?.let { atlasImageRefFromRaw(it, false) }
        ?: value.local?.takeIf(::safeLocal)?.let { atlasImageRefFromRaw(it, false) } ?: AtlasImageRef.Missing
    fun safe(metadata: MovieMetadata) = metadata.copy(synopsis = cleanDetailDescription(metadata.synopsis),
        genre = cleanDetailDescription(metadata.genre, 100), releaseYear = detailReleaseYear(metadata.releaseYear),
        durationMinutes = com.atlas.atlastv.core.media.MediaRuntime.validMinutes(metadata.durationMinutes), backdrop = image(image(metadata.backdrop)), poster = image(image(metadata.poster)))
    fun encode(metadata: MovieMetadata): String? {
        val value = safe(metadata).takeIf { it.hasDetails() } ?: return null
        val json = gson.toJson(Payload(synopsis = value.synopsis, genre = value.genre, year = value.releaseYear,
            minutes = value.durationMinutes, backdrop = image(value.backdrop), poster = image(value.poster)))
        return json.takeIf { it.toByteArray(Charsets.UTF_8).size <= MaxEntryBytes }
    }
    fun decode(json: String): MovieMetadata? = try {
        if (json.toByteArray(Charsets.UTF_8).size > MaxEntryBytes) null else {
            val value = gson.fromJson(json, Payload::class.java)
            if (value.version != 1) null else safe(MovieMetadata(value.synopsis, value.genre, value.year,
                value.minutes, image(value.backdrop), image(value.poster))).takeIf { it.hasDetails() }
        }
    } catch (_: Exception) { null }
}

internal fun MovieMetadata.hasDetails(): Boolean = synopsis != null || genre != null || releaseYear != null || durationMinutes != null ||
    !backdrop.isDisplayFallback() || !poster.isDisplayFallback()

internal fun MovieMetadata.withLastGood(previous: MovieMetadata?): MovieMetadata = copy(
    synopsis = synopsis ?: previous?.synopsis, genre = genre ?: previous?.genre, releaseYear = releaseYear ?: previous?.releaseYear,
    durationMinutes = durationMinutes ?: previous?.durationMinutes,
    backdrop = if (backdrop.isDisplayFallback()) previous?.backdrop ?: backdrop else backdrop,
    poster = if (poster.isDisplayFallback()) previous?.poster ?: poster else poster)
