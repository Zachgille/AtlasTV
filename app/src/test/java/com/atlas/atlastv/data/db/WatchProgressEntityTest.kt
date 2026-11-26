package com.atlas.atlastv.data.db

import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WatchProgressEntityTest {
    @Test
    fun entityMappingStoresOnlySafeWatchProgressMetadata() {
        val entity = WatchProgressRecord(
            contentType = WatchProgressContentType.Movie,
            streamId = 2001,
            title = "http://provider.example/movie/fake_user/fake_pass/2001.mp4",
            categoryName = "http://provider.example/live/fake_user/fake_pass/1001.ts",
            extension = ".mp4",
            durationMs = 7_200_000L,
            positionMs = 900_000L,
            updatedAtMs = 100L,
        ).toWatchProgressEntity()

        assertEquals("Untitled", entity.title)
        assertEquals("Uncategorized", entity.categoryName)
        assertEquals("mp4", entity.extension)
        assertSafe(entity.toString())
    }

    @Test
    fun entityMappingRestoresKnownContentTypes() {
        val record = WatchProgressEntity(
            contentType = "series_episode",
            streamId = 9001,
            seriesId = 3001,
            title = "Series - Pilot",
            seriesTitle = "Series",
            episodeTitle = "Pilot",
            categoryName = "Drama",
            extension = "mkv",
            durationMs = 3_600_000L,
            positionMs = 600_000L,
            updatedAtMs = 100L,
            completed = false,
        ).toWatchProgressRecordOrNull()

        assertEquals(WatchProgressContentType.SeriesEpisode, record?.contentType)
        assertEquals(9001, record?.streamId)
        assertEquals(3001, record?.seriesId)
    }

    @Test
    fun entityMappingRejectsUnknownContentType() {
        val record = WatchProgressEntity(
            contentType = "live",
            streamId = 1001,
            seriesId = null,
            title = "Live",
            seriesTitle = null,
            episodeTitle = null,
            categoryName = null,
            extension = "ts",
            durationMs = null,
            positionMs = 60_000L,
            updatedAtMs = 100L,
            completed = false,
        ).toWatchProgressRecordOrNull()

        assertEquals(null, record)
    }

    private fun assertSafe(text: String) {
        assertFalse(text.contains("provider.example", ignoreCase = true))
        assertFalse(text.contains("fake_user", ignoreCase = true))
        assertFalse(text.contains("fake_pass", ignoreCase = true))
        assertFalse(text.contains("/movie/", ignoreCase = true))
        assertFalse(text.contains("/series/", ignoreCase = true))
        assertFalse(text.contains("/live/", ignoreCase = true))
    }
}
