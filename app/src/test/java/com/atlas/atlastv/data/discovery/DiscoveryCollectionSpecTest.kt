package com.atlas.atlastv.data.discovery

import org.junit.Assert.*
import org.junit.Test
class DiscoveryCollectionSpecTest {
    @Test fun onlyKnownTypedCollectionsHaveRoutesAndHonestLabels() {
        for(type in listOf("movies","series")) for(key in listOf("new","rated","recent")+DiscoveryCollectionSpec.Genres.map {"genre-${it.lowercase(java.util.Locale.ROOT)}"}) {
            val id="${type}_$key";val spec=DiscoveryCollectionSpec.fromId(id)!!
            assertEquals(id,spec.id);assertTrue(spec.title.endsWith(if(type=="movies") "Movies" else "Shows"))
        }
        assertEquals(DiscoveryShelf.RecentlyUpdated,DiscoveryCollectionSpec.fromId("series_recent")!!.shelf)
        assertNull(DiscoveryCollectionSpec.fromId("movies_genre-netflix"));assertNull(DiscoveryCollectionSpec.fromId("movie_rated"))
        assertNull(DiscoveryCollectionSpec.fromId("movies_genre-Drama"));assertNull(DiscoveryCollectionSpec.fromId("movies_new;DROP"))
    }
}
