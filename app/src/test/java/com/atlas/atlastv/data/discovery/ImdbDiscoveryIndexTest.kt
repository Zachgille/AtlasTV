package com.atlas.atlastv.data.discovery

import org.junit.Assert.*
import org.junit.Test

class ImdbDiscoveryIndexTest {
    private fun index(vararg rows: String) = ImdbDiscoveryIndex.read(("imdbId\tcontentType\tprimaryTitle\toriginalTitle\tyear\tratingMilli\tvotes\tgenres\n"+rows.joinToString("\n")).reader().buffered())
    @Test fun independentExactTitleYearTypeAndAliasesMatchWithoutGuessingRemakes() {
        val data=index("tt0000001\tmovie\tA Movie\tOriginal Movie\t2025\t8100\t20000\tDrama,Crime", "tt0000002\tseries\tA Movie\tA Movie\t2025\t9000\t30000\tComedy")
        assertEquals("tt0000001",data.match("movie","A Movie (2025) HEVC",2025)!!.imdbId)
        assertEquals("tt0000001",data.match("movie","Original Movie",2025)!!.imdbId)
        assertEquals("tt0000002",data.match("series","A Movie",2025)!!.imdbId)
        assertNull(data.match("movie","A Movie",1995));assertNull(data.match("movie","A Movie",null))
        assertNull(data.match("movie","A Movie Extended",2025))
    }
    @Test fun ambiguousSameYearTitlesAndTooFewVotesNeverProduceHighlyRatedGuesses() {
        val data=index("tt0000001\tmovie\tAmbiguous\tAmbiguous\t2025\t8100\t2000\tDrama", "tt0000002\tmovie\tAmbiguous\tAmbiguous\t2025\t9000\t3000\tComedy", "tt0000003\tmovie\tToo few\tToo few\t2025\t10000\t999\tDrama")
        assertNull(data.match("movie","Ambiguous",2025));assertNull(data.match("movie","Too few",2025))
    }
    @Test fun malformedDatasetFailsInsteadOfPublishingPartialArbitraryScores() {
        try {index("malformed");fail("Expected dataset rejection") }catch(_: IllegalArgumentException){}
    }
    @Test fun compressedSnapshotIsAcceptedOnlyWithItsMatchingPinnedHash() {
        val text = "imdbId\tcontentType\tprimaryTitle\toriginalTitle\tyear\tratingMilli\tvotes\tgenres\n" +
            "tt0000001\tmovie\tA Movie\tA Movie\t2025\t8100\t20000\tDrama\n"
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(output).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        val bytes = output.toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("tt0000001", ImdbDiscoveryIndex.readVerified(bytes.inputStream(), hash).match("movie", "A Movie", 2025)!!.imdbId)
        assertThrows(IllegalArgumentException::class.java) { ImdbDiscoveryIndex.readVerified(bytes.inputStream(), "0".repeat(64)) }
        assertThrows(java.io.IOException::class.java) { ImdbDiscoveryIndex.readVerified(bytes.copyOf(bytes.size - 8).inputStream(), hash) }
    }
}
