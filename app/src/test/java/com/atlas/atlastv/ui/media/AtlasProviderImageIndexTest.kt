package com.atlas.atlastv.ui.media

import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtlasProviderImageIndexTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun persistsEveryEntryBeyondOldLimitAndRestoresAfterProcessRegistryIsCleared() {
        val file = File(temporary.root, AtlasProviderImageIndex.FileName)
        val entries = (1..6_001).map { "https://example.test/posters/$it.jpg" }.map { providerImageToken(it) to it }
        AtlasProviderImageIndex(file).registerAll(entries)
        AtlasProviderImageRegistry.clearForTests()
        val restored = AtlasProviderImageIndex(file)
        listOf(0, 2_999, 5_000, 6_000).forEach { assertEquals(entries[it].second, restored.resolve(entries[it].first)) }
        assertEquals(6_001, Gson().fromJson(file.readText(), ProviderImageIndexState::class.java).entries.size)
    }
    @Test fun immutableTokenBindingRejectsUnrelatedAndPlaybackAddresses() {
        val original = "https://example.test/poster.jpg"
        val token = providerImageToken(original)
        val index = AtlasProviderImageIndex(File(temporary.root, "index.json"))
        index.register(token, "https://example.test/other.jpg")
        index.register(token, "https://example.test/movie/private/password/100.mp4")
        assertNull(index.resolve(token))
        index.register(token, original)
        assertEquals(original, index.resolve(token))
    }
    @Test fun concurrentInstancesRetainBothUpdatesAndClearCannotResurrectOldFile() = runBlocking {
        val file = File(temporary.root, "index.json")
        val first = "https://example.test/first.jpg"
        val second = "https://example.test/second.jpg"
        val a = async { AtlasProviderImageIndex(file).registerAllAsync(listOf(providerImageToken(first) to first)) }
        val b = async { AtlasProviderImageIndex(file).registerAllAsync(listOf(providerImageToken(second) to second)) }
        a.await(); b.await()
        val index = AtlasProviderImageIndex(file)
        assertEquals(first, index.resolveAsync(providerImageToken(first)))
        assertEquals(second, index.resolveAsync(providerImageToken(second)))
        index.clearAsync()
        assertNull(AtlasProviderImageIndex(file).resolveAsync(providerImageToken(first)))
    }
    @Test fun corruptSourceIsNotReplacedAndInterruptedExportKeepsLastGoodData() {
        val file = File(temporary.root, "index.json")
        val address = "https://example.test/poster.jpg"
        file.writeText("{invalid")
        assertTrue(runCatching { AtlasProviderImageIndex(file).register(providerImageToken(address), address) }.isFailure)
        assertEquals("{invalid", file.readText())
        file.writeText("{\"entries\":{}}")
        val index = AtlasProviderImageIndex(file)
        index.register(providerImageToken(address), address)
        File(temporary.root, "index.json.pending").writeText("{interrupted")
        assertEquals(address, AtlasProviderImageIndex(file).resolve(providerImageToken(address)))
    }
    @Test fun persistentImportCanGenerateSafeRefWithoutKeepingWholeCatalogInRegistry() {
        val address = "https://example.test/poster.jpg"
        AtlasProviderImageRegistry.clearForTests()
        val ref = atlasImageRefFromRaw(address, rememberProviderAddress = false)
        assertEquals(AtlasImageState.ProviderImage, ref.state)
        assertEquals(providerImageToken(address), ref.providerImageToken)
        assertNull(AtlasProviderImageRegistry.resolve(ref.providerImageToken))
        assertFalse(Gson().toJson(ref).contains("example.test"))
    }
    @Test fun invalidAddressesCannotCreateUnresolvableCatalogTokens() {
        listOf("https://", "http://example.test/bad poster.jpg", "https://example.test/live/user/pass/10.ts").forEach {
            val ref = atlasImageRefFromRaw(it, rememberProviderAddress = false)
            assertEquals(AtlasImageState.ProviderImageHidden, ref.state)
            assertNull(ref.providerImageToken)
        }
    }
    @Test fun fastTokenEncodingIsCompatibleWithPersistedSha256Addresses() {
        assertEquals("provider-image://sha256/3f383d6e123a43ce5a9e60c4e0ecdb740d2aceb21a27e087ed94cbad05c40697", providerImageToken(" https://example.test/poster.jpg "))
    }
}
