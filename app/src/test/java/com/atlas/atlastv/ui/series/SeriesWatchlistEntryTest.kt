package com.atlas.atlastv.ui.series

import com.atlas.atlastv.data.db.*
import org.junit.Assert.*
import org.junit.Test

class SeriesWatchlistEntryTest {
    @Test fun currentShowMetadataWinsOverTheSavedSnapshot() {
        val current = SeriesItem("Current English Show", "Shows", 1)
        val item = SeriesWatchlistEntry(SeriesWatchlistEntity(1, "Old", "Old", 5), current.toSafeCatalogEntity(0, 1)).toSeriesItem()
        assertEquals(current, item); assertTrue(item.available)
    }
    @Test fun unknownOrKnownForeignMissingSnapshotsKeepSafeRemovableIdentity() {
        for (saved in listOf(SeriesWatchlistEntity(1, null, null, 1), SeriesWatchlistEntity(2, "French Title", "FRENCH SERIES", 1))) {
            val item = SeriesWatchlistEntry(saved, null).toSeriesItem()
            assertEquals("Unavailable show", item.name); assertFalse(item.available)
            assertEquals(saved.seriesId, item.seriesId); assertEquals("No longer in your library", item.categoryName)
        }
    }
}
