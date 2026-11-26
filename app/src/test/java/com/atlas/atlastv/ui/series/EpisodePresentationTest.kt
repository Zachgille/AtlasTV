package com.atlas.atlastv.ui.series

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodePresentationTest {
    private fun episode(title: String) = SeriesEpisodeItem(title, 1, 1, 7, "mp4", "")
    @Test fun removesRepeatedShowAndCodesButKeepsTheEpisodeName() {
        assertEquals("Woe Has Joined", episode("Blindspot (2015) - S01E01 - Woe Has Joined").displayEpisodeName("Blindspot (2015)"))
        assertEquals("Episode 1", episode("S1E1 Drakryttarna De Nio Rikena - S01E01").displayEpisodeName("Drakryttarna De Nio Rikena"))
        assertEquals("Pilot", episode("Pilot").displayEpisodeName("Blindspot"))
        assertEquals("S1 E1", episode("Pilot").shortEpisodeCode())
    }
    @Test fun preservesPunctuationAndOrdinaryNames() {
        assertEquals("The S-Team", episode("The S-Team").displayEpisodeName("Show"))
        assertEquals("Episode 1", episode("Show - S01E01").displayEpisodeName("Show"))
    }
}
