package com.atlas.atlastv.ui.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTextNormalizerTest {
    @Test fun uppercaseWordsAndAmbiguousBrandLanguageNamesAreTitles() {
        for (title in listOf("FROM (2022)", "IT (2017)", "US (2019)", "UP (2009)", "DARK (2017)",
            "THE LAST OF US (2023)", "IT FOLLOWS (2014)", "MAX PAYNE (2008)", "IN THE AIR", "LOVE - ACTUALLY")) {
            assertEquals(title, title, cleanProviderTitle(title).cleanTitle)
            assertTrue(displayTitle(cleanProviderTitle(title).cleanTitle).isNotBlank())
        }
    }

    @Test fun explicitKnownMarkersAreRemovedWithoutEatingTheUppercaseTitle() {
        assertEquals("FROM (2022)", cleanProviderTitle("MAX - FROM (2022) (US)").cleanTitle)
        assertEquals("FROM (2022)", cleanProviderTitle("EN - FROM (2022)").cleanTitle)
        assertEquals("IT (2017)", cleanProviderTitle("[HD] [EN] IT (2017)").cleanTitle)
        assertEquals("US (2019)", cleanProviderTitle("(NF) US (2019)").cleanTitle)
        assertEquals("THE LAST OF US (2023)", cleanProviderTitle("4K-EN - THE LAST OF US (2023)").cleanTitle)
        assertEquals("US - (2019)", cleanProviderTitle("US - (2019)").cleanTitle)
        assertEquals("2022", cleanProviderTitle("2022").cleanTitle)
        assertTrue(searchableTitleText("MAX - FROM (2022) (US)").contains("FROM"))
    }
    @Test
    fun removesProviderLanguageAndQualityPrefixesFromDisplayTitles() {
        assertEquals("Kung Fu Panda 4 (2024)", cleanProviderTitle("4K-EN - Kung Fu Panda 4 (2024)").cleanTitle)
        assertEquals("Family Guy", cleanProviderTitle("EN - Family Guy (US)").cleanTitle)
        assertEquals("Family Guy", cleanProviderTitle("NF - Family Guy").cleanTitle)
    }

    @Test
    fun doesNotStripNormalTitleWordsThatLookLikeShortPrefixes() {
        assertEquals("Kung Fu Panda 4", cleanProviderTitle("Kung Fu Panda 4").cleanTitle)
        assertEquals("Up", cleanProviderTitle("Up").cleanTitle)
    }

    @Test
    fun searchTextKeepsCleanAndOriginalTitleTokens() {
        val searchable = searchableTitleText("4K-EN - Kung Fu Panda 4 (2024)")

        assertTrue(searchable.contains("Kung Fu Panda 4"))
        assertTrue(searchable.contains("4K-EN"))
    }

    @Test
    fun categoryCleanupRemovesUsefulEnglishAndQualityPrefixesOnly() {
        assertEquals("KIDS 4K 3840P", cleanProviderCategory("EN - KIDS 4K 3840P"))
        assertEquals("Movies", cleanProviderCategory("4K - Movies"))
    }
}
