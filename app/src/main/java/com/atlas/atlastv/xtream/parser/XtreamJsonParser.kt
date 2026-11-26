package com.atlas.atlastv.xtream.parser

import com.atlas.atlastv.xtream.model.XtreamAccountInfo
import com.atlas.atlastv.xtream.model.XtreamCategory
import com.atlas.atlastv.xtream.model.XtreamLiveStream
import com.atlas.atlastv.xtream.model.XtreamSeries
import com.atlas.atlastv.xtream.model.XtreamSeriesEpisode
import com.atlas.atlastv.xtream.model.XtreamSeriesInfo
import com.atlas.atlastv.xtream.model.XtreamServerInfo
import com.atlas.atlastv.xtream.model.XtreamVodStream
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import java.io.StringReader

class XtreamJsonParser {
    fun parseMovieInfo(json: String): XtreamParseResult<com.atlas.atlastv.xtream.model.XtreamMovieInfo> =
        parseObject(json) { root ->
            val info = root.obj("info")
            val backdrop = info?.get("backdrop_path")
            com.atlas.atlastv.xtream.model.XtreamMovieInfo(
                plot = info?.string("plot") ?: info?.string("description"),
                genre = info?.string("genre"),
                releaseDate = info?.string("releasedate") ?: info?.string("releaseDate") ?: info?.string("release_date"),
                durationSeconds = XtreamRuntime.millis(info?.string("duration_secs"), info?.string("duration"))?.let { ((it + 999) / 1000).toInt() },
                cover = info?.string("movie_image") ?: info?.string("cover_big") ?: info?.string("cover"),
                backdrops = when {
                    backdrop?.isJsonArray == true -> backdrop.asJsonArray.filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }.take(12).map { it.asString }
                    backdrop?.isJsonPrimitive == true && backdrop.asJsonPrimitive.isString -> listOf(backdrop.asString)
                    else -> emptyList()
                },
            )
        }

    fun parseAccountInfo(json: String): XtreamParseResult<XtreamAccountInfo> =
        parseObject(json) { root ->
            val userInfo = root.obj("user_info")
            val serverInfo = root.obj("server_info")
            XtreamAccountInfo(
                auth = userInfo?.int("auth"),
                status = userInfo?.string("status"),
                message = userInfo?.string("message"),
                activeConnections = userInfo?.int("active_cons"),
                maxConnections = userInfo?.int("max_connections"),
                expirationDate = userInfo?.string("exp_date"),
                serverInfo = serverInfo?.let {
                    XtreamServerInfo(
                        url = it.string("url"),
                        port = it.string("port"),
                        httpsPort = it.string("https_port"),
                        serverProtocol = it.string("server_protocol"),
                        timeNow = it.string("time_now"),
                    )
                },
            )
        }

    fun parseCategories(json: String): XtreamParseResult<List<XtreamCategory>> =
        parseArray(json) { item ->
            XtreamCategory(
                categoryId = item.string("category_id"),
                categoryName = item.string("category_name"),
                parentId = item.string("parent_id"),
            )
        }

    fun parseLiveStreams(json: String): XtreamParseResult<List<XtreamLiveStream>> =
        parseArray(json) { item ->
            XtreamLiveStream(
                num = item.int("num"),
                name = item.string("name"),
                streamType = item.string("stream_type"),
                streamId = item.int("stream_id"),
                streamIcon = item.string("stream_icon"),
                epgChannelId = item.string("epg_channel_id"),
                added = item.string("added"),
                categoryId = item.string("category_id"),
                customSid = item.string("custom_sid"),
                tvArchive = item.int("tv_archive"),
                directSource = item.string("direct_source"),
            )
        }

    fun parseVodStreams(json: String): XtreamParseResult<List<XtreamVodStream>> =
        parseArray(json) { item ->
            XtreamVodStream(
                num = item.int("num"),
                name = item.string("name"),
                streamType = item.string("stream_type"),
                streamId = item.int("stream_id"),
                streamIcon = item.string("stream_icon"),
                rating = item.string("rating"),
                rating5Based = item.string("rating_5based"),
                added = item.string("added"),
                categoryId = item.string("category_id"),
                containerExtension = item.string("container_extension"),
            )
        }

    fun parseSeries(json: String): XtreamParseResult<List<XtreamSeries>> =
        parseArray(json) { item ->
            XtreamSeries(
                num = item.int("num"),
                name = item.string("name"),
                seriesId = item.int("series_id"),
                cover = item.string("cover"),
                plot = item.string("plot"),
                cast = item.string("cast"),
                director = item.string("director"),
                genre = item.string("genre"),
                releaseDate = item.string("releaseDate") ?: item.string("release_date"),
                lastModified = item.string("last_modified"),
                rating = item.string("rating"),
                rating5Based = item.string("rating_5based"),
                categoryId = item.string("category_id"),
            )
        }

    fun parseSeriesInfo(json: String): XtreamParseResult<XtreamSeriesInfo> =
        parseObject(json) { root ->
            val episodesRoot = root.obj("episodes")
            val episodes = episodesRoot
                ?.entrySet()
                ?.flatMap { (season, element) ->
                    val seasonNumber = season.toIntOrNull()
                    if (!element.isJsonArray) {
                        emptyList()
                    } else {
                        element.asJsonArray
                            .filter { it.isJsonObject }
                            .map { episode ->
                                val item = episode.asJsonObject
                                XtreamSeriesEpisode(
                                    id = item.int("id"),
                                    episodeNumber = item.int("episode_num"),
                                    title = item.string("title"),
                                    seasonNumber = seasonNumber,
                                    containerExtension = item.string("container_extension"),
                                    durationMs = episodeDurationMs(item.obj("info")),
                                    thumbnail = item.obj("info")?.string("movie_image"),
                                )
                            }
                    }
                }
                .orEmpty()
            seriesMetadata(root.obj("info"), episodes)
        }

    /** Home previews need show information without materializing an episode collection. */
    fun parseSeriesMetadata(json: String): XtreamParseResult<XtreamSeriesInfo> =
        parseObject(json) { root -> seriesMetadata(root.obj("info"), emptyList()) }

    private fun episodeDurationMs(info: JsonObject?): Long? =
        XtreamRuntime.millis(info?.string("duration_secs"), info?.string("duration"))

    private fun seriesMetadata(info: JsonObject?, episodes: List<XtreamSeriesEpisode>): XtreamSeriesInfo {
            val backdrop = info?.get("backdrop_path")
            return XtreamSeriesInfo(episodes = episodes,
                plot = info?.string("plot"), genre = info?.string("genre"),
                releaseDate = info?.string("releaseDate") ?: info?.string("release_date"),
                cover = info?.string("cover"),
                backdrops = when {
                    backdrop?.isJsonArray == true -> backdrop.asJsonArray.filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }.take(12).map { it.asString }
                    backdrop?.isJsonPrimitive == true && backdrop.asJsonPrimitive.isString -> listOf(backdrop.asString)
                    else -> emptyList()
                })
    }

    private fun <T> parseObject(json: String, mapper: (JsonObject) -> T): XtreamParseResult<T> =
        try {
            val root = parseLenient(json)
            if (!root.isJsonObject) {
                XtreamParseResult.Failure("Expected JSON object.")
            } else {
                XtreamParseResult.Success(mapper(root.asJsonObject))
            }
        } catch (exception: Exception) {
            XtreamParseResult.Failure(exception.toParseFailureMessage())
        }

    private fun <T> parseArray(json: String, mapper: (JsonObject) -> T): XtreamParseResult<List<T>> =
        try {
            val root = parseLenient(json)
            if (!root.isJsonArray) {
                XtreamParseResult.Failure("Expected JSON array.")
            } else {
                XtreamParseResult.Success(
                    root.asJsonArray
                        .filter { it.isJsonObject }
                        .map { mapper(it.asJsonObject) },
                )
            }
        } catch (exception: Exception) {
            XtreamParseResult.Failure(exception.toParseFailureMessage())
        }

    private fun parseLenient(json: String): JsonElement =
        JsonReader(StringReader(json)).use { reader ->
            reader.setStrictness(Strictness.LENIENT)
            JsonParser.parseReader(reader)
        }

    private fun Exception.toParseFailureMessage(): String {
        val detail = message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        val causeDetail = cause
            ?.let { cause -> cause.message?.takeIf { it.isNotBlank() }?.let { ": ${cause.javaClass.simpleName}: $it" } ?: ": ${cause.javaClass.simpleName}" }
            .orEmpty()
        return "Malformed JSON: ${javaClass.simpleName}$detail$causeDetail"
    }

    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.string(name: String): String? =
        primitive(name)?.asString?.takeUnless { it.isBlank() }

    private fun JsonObject.int(name: String): Int? =
        primitive(name)?.let { primitive ->
            runCatching { primitive.asInt }.getOrNull()
                ?: runCatching { primitive.asString.toInt() }.getOrNull()
        }

    private fun JsonObject.primitive(name: String): com.google.gson.JsonPrimitive? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
}
