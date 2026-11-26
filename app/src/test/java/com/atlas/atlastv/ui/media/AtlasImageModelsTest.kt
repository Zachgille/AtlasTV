package com.atlas.atlastv.ui.media

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AtlasImageModelsTest {
    @Before
    fun setUp() {
        AtlasProviderImageRegistry.clearForTests()
    }

    @Test
    fun providerPosterUrlBecomesOpaqueTokenWithoutRawUrlInState() {
        val ref = atlasImageRefFromRaw("https://example.test/movie/fake_user/fake_pass/poster.jpg")
        val json = Gson().toJson(ref)

        assertEquals(AtlasImageState.ProviderImage, ref.state)
        assertTrue(ref.providerImageToken!!.startsWith("provider-image://sha256/"))
        assertNotNull(AtlasProviderImageRegistry.resolve(ref.providerImageToken))
        assertFalse(json.contains("example.test"))
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains("/movie/"))
    }

    @Test
    fun rawPlaybackUrlsRemainHiddenFallbacks() {
        val ref = atlasImageRefFromRaw("https://example.test/movie/fake_user/fake_pass/1234.mp4")
        val json = Gson().toJson(ref)

        assertEquals(AtlasImageState.ProviderImageHidden, ref.state)
        assertEquals(null, ref.providerImageToken)
        assertFalse(json.contains("example.test"))
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains("/movie/"))
    }

    @Test
    fun providerImageTokenCanBeRehydratedWithoutRawUrl() {
        val original = atlasImageRefFromRaw("https://example.test/images/poster.webp")
        val restored = atlasImageRefFromRaw(original.providerImageToken)

        assertEquals(AtlasImageState.ProviderImage, restored.state)
        assertEquals(original.providerImageToken, restored.providerImageToken)
        assertFalse(Gson().toJson(restored).contains("example.test"))
    }

    @Test
    fun providerImageCacheUsesTokenFileNamesOnly() {
        val ref = atlasImageRefFromRaw("https://example.test/movie/fake_user/fake_pass/poster.jpg")
        val cache = AtlasProviderImageCache(tempCacheDir())

        val fileName = cache.fileNameForToken(ref.providerImageToken)!!

        assertTrue(fileName.endsWith(".img"))
        assertFalse(fileName.contains("example.test"))
        assertFalse(fileName.contains("fake_user"))
        assertFalse(fileName.contains("fake_pass"))
        assertFalse(fileName.contains("/movie/"))
    }

    @Test
    fun providerImageCacheRestoresBytesAfterRegistryIsCleared() {
        val ref = atlasImageRefFromRaw("https://example.test/images/poster.jpg")
        val cache = AtlasProviderImageCache(tempCacheDir())
        val bytes = byteArrayOf(1, 2, 3, 4)

        assertTrue(cache.write(ref.providerImageToken, bytes))
        AtlasProviderImageRegistry.clearForTests()

        assertEquals(bytes.toList(), requireNotNull(cache.read(ref.providerImageToken)).toList())
        assertNull(AtlasProviderImageRegistry.resolve(ref.providerImageToken))
    }

    @Test
    fun providerImageIndexRehydratesTokenAfterRegistryIsCleared() {
        val rawUrl = "https://example.test/images/poster.jpg"
        val ref = atlasImageRefFromRaw(rawUrl)
        val file = File(tempCacheDir(), AtlasProviderImageIndex.FileName)
        val index = AtlasProviderImageIndex(file)

        index.register(ref.providerImageToken, rawUrl)
        AtlasProviderImageRegistry.clearForTests()

        assertEquals(rawUrl, index.resolve(ref.providerImageToken))
        assertNull(AtlasProviderImageRegistry.resolve(ref.providerImageToken))
        assertFalse(Gson().toJson(ref).contains("example.test"))
    }

    @Test
    fun providerImageIndexRejectsPlaybackUrls() {
        val ref = atlasImageRefFromRaw("https://example.test/images/poster.jpg")
        val index = AtlasProviderImageIndex(File(tempCacheDir(), AtlasProviderImageIndex.FileName))

        index.register(ref.providerImageToken, "https://example.test/movie/fake_user/fake_pass/1234.mp4")

        assertNull(index.resolve(ref.providerImageToken))
    }

    @Test
    fun providerImageCacheRejectsUnsafeOrOversizedEntries() {
        val cache = AtlasProviderImageCache(tempCacheDir(), maxBytes = 4)

        assertFalse(cache.write("not-a-token", byteArrayOf(1, 2, 3)))
        assertFalse(cache.write("provider-image://sha256/nothex", byteArrayOf(1, 2, 3)))
        assertFalse(cache.write("provider-image://sha256/${"a".repeat(64)}", byteArrayOf(1, 2, 3, 4, 5)))
        assertNull(cache.read("not-a-token"))
    }

    private fun tempCacheDir(): File =
        Files.createTempDirectory("atlas-provider-image-cache-").toFile().also { it.deleteOnExit() }
}
