package com.atlas.atlastv.ui.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveTvDetailsTest {
    @Test
    fun mapsSelectedChannelDetailsFromVisibleChannelList() {
        val state = readyState(selectedStreamId = 102)

        val details = state.selectedChannelDetails()

        requireNotNull(details)
        assertEquals("Sports Two", details.channelName)
        assertEquals("Sports", details.categoryName)
        assertEquals(2, details.selectedIndex)
        assertEquals(3, details.visibleCount)
        assertEquals(102, details.streamId)
        assertEquals("cached", details.cacheSource)
        assertEquals(12_000L, details.cacheAgeMs)
        assertEquals("Provider order", details.sortModeLabel)
        assertEquals("Press Select to play", details.playbackHint)
    }

    @Test
    fun detailsUseRedactedStreamUrlOnly() {
        val details = readyState(selectedStreamId = 101).selectedChannelDetails()

        requireNotNull(details)
        assertEquals("http://REDACTED_HOST/live/REDACTED/REDACTED/101.ts", details.redactedStreamUrl)
        assertFalse(details.redactedStreamUrl.contains("fake_user"))
        assertFalse(details.redactedStreamUrl.contains("fake_pass"))
        assertFalse(details.redactedStreamUrl.contains("example.test"))
    }

    @Test
    fun detailsUpdateWhenSelectedChannelChanges() {
        val first = readyState(selectedStreamId = 101).selectedChannelDetails()
        val second = readyState(selectedStreamId = 103).selectedChannelDetails()

        requireNotNull(first)
        requireNotNull(second)
        assertEquals("News One", first.channelName)
        assertEquals("Movies Three", second.channelName)
        assertEquals(1, first.selectedIndex)
        assertEquals(3, second.selectedIndex)
    }

    private fun readyState(selectedStreamId: Int): LiveTvCatalogState.Ready =
        LiveTvCatalogState.Ready(
            source = "cached",
            cacheAgeMs = 12_000L,
            channels = listOf(
                channel("News One", "News", 101),
                channel("Sports Two", "Sports", 102),
                channel("Movies Three", "Movies", 103),
            ),
            categories = listOf(LiveTvCategory(id = LiveTvCategoryIds.All, name = "All", channelCount = 3)),
            selectedChannelStreamId = selectedStreamId,
        )

    private fun channel(name: String, category: String, streamId: Int): LiveTvChannel =
        LiveTvChannel(
            name = name,
            categoryName = category,
            streamId = streamId,
            containerExtension = "ts",
            redactedStreamUrl = "http://REDACTED_HOST/live/REDACTED/REDACTED/$streamId.ts",
        )
}
