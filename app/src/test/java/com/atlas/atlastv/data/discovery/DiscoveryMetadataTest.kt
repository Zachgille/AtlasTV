package com.atlas.atlastv.data.discovery

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DiscoveryMetadataTest {
    private val now = Instant.parse("2026-10-04T18:00:00Z").toEpochMilli()
    @Test fun providerRatingsNeverPopulateIndependentRecommendationScores() {
        val row = DiscoveryMetadata.create("movie",1,"Title (2026)",5,now,rating="10",fiveBased="5")
        assertNull(row.ratingMilli); assertNull(row.ratingSource); assertNull(row.ratingVotes); assertNull(row.imdbId)
    }
    @Test fun addedAndModifiedSecondsAndMillisAreValidatedSeparately() {
        assertEquals(1791124020000L, DiscoveryMetadata.timestamp("1791124020", now))
        assertEquals(1791124020000L, DiscoveryMetadata.timestamp("1791124020000", now))
        for (bad in listOf("0", "-1", "1", "9223372036854775807", "1799999999", "bad")) assertNull(DiscoveryMetadata.timestamp(bad, now))
        val show = DiscoveryMetadata.create("series", 1, "Show", 7, now, changed="1791124020")
        assertNull(show.addedAtMs); assertNotNull(show.changedAtMs)
    }
    @Test fun releaseDatesTitleYearsAndFutureUnknownValuesHaveHonestProvenance() {
        assertEquals(20260910 to "provider-date", DiscoveryMetadata.release("2026-09-10", "Show", now))
        assertEquals(20250101 to "title-year", DiscoveryMetadata.release(null, "Movie (2025) HEVC", now))
        assertEquals(20260101 to "provider-year", DiscoveryMetadata.release("2026", "Show", now))
        assertNull(DiscoveryMetadata.release("2026-12-01", "Movie (2026)", now))
        assertNull(DiscoveryMetadata.release(null, "1917", now))
        assertNull(DiscoveryMetadata.release(null, "Future (2027)", now))
    }
    @Test fun dedupPreservesRemakesEditionsCountryAndUnknownIdentity() {
        fun row(id: Int, title: String) = DiscoveryMetadata.create("movie", id, title, 5, now)
        assertEquals(row(1,"Movie (2025) HEVC").variantKey, row(2,"Movie (2025) [4K]").variantKey)
        assertNotEquals(row(1,"Movie (2025)").variantKey, row(2,"Movie (1995)").variantKey)
        assertNotEquals(row(1,"Movie (2025)").variantKey, row(2,"Movie Extended (2025)").variantKey)
        assertNotEquals(row(1,"Movie (2025)").variantKey, row(2,"Movie US (2025)").variantKey)
        assertNotEquals(row(1,"Movie").variantKey, row(2,"Movie").variantKey)
    }
    @Test fun genresAreWhitelistedProviderFieldsNotPlatformQualityOrLanguage() {
        assertEquals("|Action|Adventure|Drama|Sci-Fi|", DiscoveryMetadata.genres("Action & Adventure, Drama, Sci-Fi, Drama"))
        assertEquals("", DiscoveryMetadata.genres("Netflix, HEVC, English, http://private.test"))
        assertEquals("|Fantasy|Sci-Fi|", DiscoveryMetadata.genres("Sci-Fi & Fantasy"))
    }
}
