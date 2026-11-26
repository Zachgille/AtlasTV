package com.atlas.atlastv.ui.series

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class SeriesWatchlistStoreTest {
    @Test fun savingBoundedRowsPreservesUnseenTitlesAndExplicitRemoval() {
        val store = SeriesWatchlistStore(tempFile())
        val hidden = SeriesItem("Hidden show", "Shows", 1)
        val visible = SeriesItem("Visible show", "Shows", 2)
        store.saveWatchlist(listOf(hidden), setOf(1))
        store.saveWatchlist(listOf(visible), setOf(1, 2, 3))
        assertEquals(setOf(1, 2, 3), store.loadWatchlistIds())
        store.saveWatchlist(listOf(visible), setOf(1, 3))
        assertEquals(setOf(1, 3), store.loadWatchlistIds())
    }

    @Test
    fun persistsOnlySafeSeriesSnapshots() {
        val file = tempFile()
        val store = SeriesWatchlistStore(file)
        val series = listOf(
            SeriesItem(
                name = "Fixture Series",
                categoryName = "Fixture Drama",
                seriesId = 3001,
            ),
        )

        store.saveWatchlist(series, setOf(3001))

        val json = file.readText(Charsets.UTF_8)
        assertEquals(setOf(3001), store.loadWatchlistIds())
        assertFalse(json.contains("REDACTED_HOST/series"))
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains("example.test"))
    }

    @Test
    fun clearRemovesStoredWatchlist() {
        val file = tempFile()
        val store = SeriesWatchlistStore(file)

        store.saveWatchlist(
            listOf(SeriesItem("Fixture Series", "Fixture Drama", 3001)),
            setOf(3001),
        )
        store.clear()

        assertFalse(file.exists())
        assertEquals(emptySet<Int>(), store.loadWatchlistIds())
    }

    private fun tempFile(): File =
        File.createTempFile("series-watchlist", ".json").also { it.deleteOnExit() }
}
