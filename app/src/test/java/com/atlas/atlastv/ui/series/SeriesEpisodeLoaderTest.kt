package com.atlas.atlastv.ui.series

import com.atlas.atlastv.core.network.TextDownloadResult
import com.atlas.atlastv.core.network.TextDownloader
import com.atlas.atlastv.xtream.model.XtreamCredentials
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SeriesEpisodeLoaderTest {
    @Test
    fun mapsEpisodeInfoToSafeRedactedMetadata() = runBlocking {
        val loader = SeriesEpisodeLoader(
            downloader = FakeDownloader(
                TextDownloadResult.Success(
                    text = """
                        {
                          "episodes": {
                            "1": [
                              { "id": "901", "episode_num": "1", "title": "Pilot", "container_extension": "mkv" }
                            ]
                          }
                        }
                    """.trimIndent(),
                    byteCount = 10,
                )
            ),
        )

        val result = loader.loadEpisodes(XtreamCredentials("http://example.test", "fake_user", "fake_pass"), seriesId = 301) as SeriesEpisodeLoadResult.Success
        val episode = result.episodes.single()

        assertEquals("Pilot", episode.title)
        assertEquals(901, episode.episodeId)
        assertEquals(1, episode.seasonNumber)
        assertEquals(1, episode.episodeNumber)
        assertEquals("mkv", episode.containerExtension)
        assertEquals("http://REDACTED_HOST/series/REDACTED/REDACTED/901.mkv", episode.redactedStreamUrl)
        assertFalse(episode.redactedStreamUrl.contains("example.test"))
        assertFalse(episode.redactedStreamUrl.contains("fake_user"))
        assertFalse(episode.redactedStreamUrl.contains("fake_pass"))
    }

    @Test
    fun cleansProviderPrefixesFromEpisodeTitles() = runBlocking {
        val loader = SeriesEpisodeLoader(
            downloader = FakeDownloader(
                TextDownloadResult.Success(
                    text = """
                        {
                          "episodes": {
                            "1": [
                              { "id": "902", "episode_num": "1", "title": "VP - Family Guy - S01E01", "container_extension": "mp4" }
                            ]
                          }
                        }
                    """.trimIndent(),
                    byteCount = 10,
                ),
            ),
        )

        val result = loader.loadEpisodes(XtreamCredentials("http://example.test", "fake_user", "fake_pass"), seriesId = 301) as SeriesEpisodeLoadResult.Success

        assertEquals("Family Guy - S01E01", result.episodes.single().title)
    }

    @Test
    fun defaultEpisodeLoadAllowsMoreThanTwentyFourEpisodes() = runBlocking {
        val episodes = (1..30).joinToString(",") { number ->
            """{ "id": "${9000 + number}", "episode_num": "$number", "title": "Episode $number", "container_extension": "mp4" }"""
        }
        val loader = SeriesEpisodeLoader(
            downloader = FakeDownloader(
                TextDownloadResult.Success(
                    text = """{ "episodes": { "1": [ $episodes ] } }""",
                    byteCount = 10,
                ),
            ),
        )

        val result = loader.loadEpisodes(XtreamCredentials("http://example.test", "fake_user", "fake_pass"), seriesId = 301) as SeriesEpisodeLoadResult.Success

        assertEquals(30, result.episodes.size)
        assertEquals(9030, result.episodes.last().episodeId)
    }

    private class FakeDownloader(private val result: TextDownloadResult) : TextDownloader {
        override suspend fun download(url: String): TextDownloadResult = result
    }

    @Test fun selectedShowIncludesEpisodesPastTheOldThousandEpisodeLimit() = runBlocking {
        val seasons = (1..24).joinToString(",") { season ->
            val episodes = (1..64).joinToString(",") { number ->
                """{"id":"${season * 1000 + number}","episode_num":$number,"title":"Episode $number","container_extension":"mp4"}"""
            }
            "\"$season\":[$episodes]"
        }
        val loader = SeriesEpisodeLoader(FakeDownloader(TextDownloadResult.Success("{\"episodes\":{$seasons}}", 100)))
        val result = loader.loadEpisodes(XtreamCredentials("http://example.test", "user", "pass"), 1) as SeriesEpisodeLoadResult.Success
        assertEquals(1536, result.episodes.size)
        assertEquals(24064, result.episodes.last().episodeId)
        assertEquals(24, result.episodes.last().seasonNumber)
    }
}
