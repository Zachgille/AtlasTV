package com.atlas.atlastv.data.catalog

import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.xtream.model.XtreamCredentials
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CatalogTitleRepairTest {
    private val account = XtreamCredentials("http://fixture.test", "fixture-user", "fixture-password")
    private fun row(id: Int = 7, type: String = "series") = SafeCatalogItemEntity(
        type, id, "(2022)", " 2022 max from 2022 us ", "one", "English", 3, containerExtension = null,
        posterState = "Missing", posterSafeUri = null, posterProviderToken = null, posterRedactedSource = null, updatedAtMs = 9)

    @Test fun recoversOnlyProvenOriginalNamesAndNeverGuessesFromUnrelatedIds() {
        assertEquals("FROM (2022)", recoveredCatalogTitle(row(), "MAX - FROM (2022) (US)"))
        assertNull(recoveredCatalogTitle(row(), "MAX - LOST (2022) (US)"))
        assertNull(recoveredCatalogTitle(row(), "http://fixture.test/secret"))
        assertNull(recoveredCatalogTitle(row().copy(title = "Keep this title"), "MAX - FROM (2022) (US)"))
        assertNull(recoveredCatalogTitle(row().copy(normalizedTitle = "2022 fr foreign 2022"), "FR - Foreign (2022)"))
        assertNull(recoveredCatalogTitle(row(), "(2022)"))
    }

    @Test fun categoryRequestsAreSharedButMovieAndShowIdentitiesStaySeparate() = runTest {
        val series = row()
        val movie = row(type = "movie")
        val saved = mutableListOf<Pair<String, Int>>()
        val requests = mutableListOf<Pair<String, String>>()
        var updates = 0
        val repair = CatalogTitleRepair({ listOf(series, series.copy(itemId = 8), movie) }, { value, title ->
            assertEquals("FROM (2022)", title); saved += value.contentType to value.itemId; true
        }) { _, type, category ->
            requests += type to category
            listOf(ProviderTitle(7, "MAX - FROM (2022) (US)"), ProviderTitle(8, "MAX - FROM (2022) (US)"))
        }
        assertEquals(3, repair.run({ account }) { updates++ })
        assertEquals(listOf("series" to "one", "movie" to "one"), requests)
        assertEquals(setOf("series" to 7, "series" to 8, "movie" to 7), saved.toSet())
        assertEquals(2, updates)
    }

    @Test fun accountSwitchAndDuplicateSourceIdsCannotCommitARepair() = runTest {
        var owner = account
        var writes = 0
        val repair = CatalogTitleRepair({ listOf(row()) }, { _, _ -> writes++; true }) { _, _, _ ->
            owner = XtreamCredentials("http://other.test", "different", "different")
            listOf(ProviderTitle(7, "MAX - FROM (2022) (US)"))
        }
        assertEquals(0, repair.run({ owner })); assertEquals(0, writes)
        val duplicates = CatalogTitleRepair({ listOf(row()) }, { _, _ -> writes++; true }) { _, _, _ ->
            listOf(ProviderTitle(7, "MAX - FROM (2022) (US)"), ProviderTitle(7, "Other"))
        }
        assertEquals(0, duplicates.run({ account })); assertEquals(0, writes)
    }

    @Test fun offlinePassStopsAfterThreeFailuresAndDoesNotInvalidateTheLibrary() = runTest {
        var requests = 0
        val repair = CatalogTitleRepair({ (1..10).map { row(it).copy(categoryId = "$it") } }, { _, _ -> fail("No write"); false }) { _, _, _ ->
            requests++; null
        }
        assertEquals(0, repair.run({ account }) { fail("No refresh") })
        assertEquals(3, requests)
    }
}
