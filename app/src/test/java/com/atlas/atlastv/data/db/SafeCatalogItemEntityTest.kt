package com.atlas.atlastv.data.db

import com.atlas.atlastv.ui.media.AtlasImageState
import com.atlas.atlastv.ui.media.AtlasProviderImageRegistry
import com.atlas.atlastv.ui.media.atlasImageRefFromRaw
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.series.SeriesItem
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeCatalogItemEntityTest {
    @Test
    fun movieEntityStoresOnlySafeCatalogMetadata() {
        val movie = MovieItem(
            title = "Fixture Movie",
            categoryName = "Action",
            streamId = 2001,
            containerExtension = "mkv",
            redactedStreamUrl = "http://REDACTED_HOST/movie/REDACTED/REDACTED/2001.mkv",
            posterImage = atlasImageRefFromRaw("http://provider.example/posters/movie.jpg"),
        )

        val entity = movie.toSafeCatalogEntity(providerOrder = 7, updatedAtMs = 100L)
        val json = Gson().toJson(entity)

        assertEquals(SafeCatalogContentTypes.Movie, entity.contentType)
        assertEquals(2001, entity.itemId)
        assertEquals("Fixture Movie", entity.title)
        assertEquals("Action", entity.categoryName)
        assertEquals("mkv", entity.containerExtension)
        assertEquals(AtlasImageState.ProviderImage.name, entity.posterState)
        assertTrue(entity.posterProviderToken!!.startsWith("provider-image://sha256/"))
        assertFalse(json.contains("provider.example"))
        assertFalse(json.contains("/movie/REDACTED"))
        assertFalse(json.contains("/movie/fake_user"))
        assertFalse(json.contains("fake_pass"))
    }

    @Test
    fun seriesEntityStoresOnlySafeCatalogMetadata() {
        val series = SeriesItem(
            name = "Fixture Series",
            categoryName = "Drama",
            seriesId = 3001,
            posterImage = atlasImageRefFromRaw("fixture://poster/series-one"),
        )

        val entity = series.toSafeCatalogEntity(providerOrder = 2, updatedAtMs = 200L)
        val restored = entity.toSeriesItem()

        assertEquals(SafeCatalogContentTypes.Series, entity.contentType)
        assertEquals(3001, entity.itemId)
        assertEquals("Fixture Series", restored.name)
        assertEquals("Drama", restored.categoryName)
        assertEquals(AtlasImageState.SafeImage, restored.posterImage.state)
        assertFalse(Gson().toJson(entity).contains("fake_user"))
        assertFalse(Gson().toJson(entity).contains("fake_pass"))
    }

    @Test
    fun unsafeDisplayTextFallsBackBeforePersistence() {
        val movie = MovieItem(
            title = "http://provider.example/movie/fake_user/fake_pass/2001.mkv",
            categoryName = "http://provider.example/series/fake_user/fake_pass/3001.mkv",
            streamId = 2001,
            containerExtension = "mp4",
            redactedStreamUrl = "http://REDACTED_HOST/movie/REDACTED/REDACTED/2001.mp4",
        )

        val entity = movie.toSafeCatalogEntity(providerOrder = 0, updatedAtMs = 1L)
        val json = Gson().toJson(entity)

        assertEquals("Movie 2001", entity.title)
        assertEquals("Uncategorized", entity.categoryName)
        assertFalse(json.contains("provider.example"))
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains("/movie/"))
        assertFalse(json.contains("/series/"))
    }

    @Test
    fun providerImageTokensRestoreWithoutRawRegistryUrl() {
        val rawPoster = "http://provider.example/images/show.png"
        val token = atlasImageRefFromRaw(rawPoster).providerImageToken!!
        AtlasProviderImageRegistry.clearForTests()

        val entity = SafeCatalogItemEntity(
            contentType = SafeCatalogContentTypes.Series,
            itemId = 3001,
            title = "Fixture Series",
            categoryName = "Drama",
            providerOrder = 0,
            containerExtension = null,
            posterState = AtlasImageState.ProviderImage.name,
            posterSafeUri = null,
            posterProviderToken = token,
            posterRedactedSource = "provider_image",
            updatedAtMs = 1L,
        )

        val restored = entity.toSeriesItem()

        assertEquals(token, restored.posterImage.providerImageToken)
        assertFalse(Gson().toJson(entity).contains("provider.example"))
    }
}
