package com.atlas.atlastv.ui.catalog

import org.junit.Assert.*
import org.junit.Test
import com.atlas.atlastv.ui.series.displayEpisodeName

class TitlePresentationTest {
    @Test fun playbackTitleUsesOneCompactEpisodeCodeWithoutTheReleaseYear() {
        assertEquals("Blindspot · S1 E1 · Woe Has Joined", playbackDisplayTitle("Blindspot (2015) - S01E01 - Woe Has Joined"))
        assertEquals("1917", playbackDisplayTitle("1917 (2019)"))
    }
    @Test fun releaseYearIsMetadataRatherThanPartOfDisplayedTitle() {
        assertEquals("Blindspot",displayTitle("Blindspot (2015)"))
        assertEquals("2015",releaseYearLabel("Blindspot (2015)"))
        assertEquals("Blindspot - Pilot",displayTitle("Blindspot (2015) - Pilot"))
    }
    @Test fun meaningfulNumbersAndNonYearParenthesesRemainPartOfTitle() {
        assertEquals("2001: A Space Odyssey",displayTitle("2001: A Space Odyssey (1968)"))
        assertEquals("1917",displayTitle("1917 (2019)"))
        assertEquals("Batman (Extended Cut)",displayTitle("Batman (Extended Cut)"))
        assertNull(releaseYearLabel("1917"))
    }

    @Test fun realProviderCodecSuffixLeavesTheDisplayWithoutChangingStoredTitle() {
        val raw = "Spiderhead (2022) HEVC"
        assertEquals("Spiderhead", displayTitle(raw))
        assertEquals("2022", releaseYearLabel(raw))

        assertEquals("Spiderhead (2022) HEVC", cleanProviderTitle(raw).cleanTitle)
        assertTrue(searchableTitleText(raw).contains(raw))
    }
    @Test fun explicitSuffixGroupsAndYearBoundedBareGroupsAreCleaned() {
        for (raw in listOf("A Movie (2020) [4K / HDR10 / HEVC]", "A Movie (2020) - 4k hdr10 hevc",
            "A Movie (2020) 4K HDR10 HEVC", "A Movie (2020) (4K HDR10 HEVC)")) {
            assertEquals(raw, "A Movie", displayTitle(raw))

        }

    }
    @Test fun explicitMixedCaseGroupsLeaveTheMovieNameClean() {
        assertEquals("A Show", displayTitle("A Show [fhd, x264]"))
        assertEquals("A Movie", displayTitle("A Movie [Dolby Vision AV1]"))
    }
    @Test fun repeatedGroupsAndCodecAliasesLeaveTheDisplayTitleClean() {
        val raw = "1917 (2019) HEVC [4K] (H.265 HDR10+)"
        assertEquals("1917", displayTitle(raw))

        assertEquals("1917", displayTitle(displayTitle(raw)))
    }
    @Test fun meaningfulWordsNumbersAndEditionNamesStayIntact() {
        for (raw in listOf("The Road to 4K", "HD", "4K", "HEVC", "[HD]", "(2020) [HD]",
            "Batman (Extended Cut)", "Film [Unknown Quality]", "Film (KR)", "Film - The HDR Story", "Film (HD Restoration)",
            "Film [4K HDR10)")) {
            assertEquals(raw, raw.replace("(2020) ", ""), displayTitle(raw))

        }
        assertEquals("Batman (Extended Cut)", displayTitle("Batman (1989) (Extended Cut) [1080p]"))

    }
    @Test fun unknownOrEmbeddedFormatWordsAreNeverSilentlyDiscarded() {
        assertEquals("4K Adventures - Pilot", displayTitle("4K Adventures (2020) - Pilot"))

        assertEquals("Film HEVC (Extended Cut)", displayTitle("Film (2020) HEVC (Extended Cut)"))
        assertEquals("Film [1080p Commentary]", displayTitle("Film (2020) [1080p Commentary]"))
    }
    @Test fun playbackAndEpisodeNamesShareTheSamePresentationWithoutChangingEpisodeIdentity() {
        assertEquals("Blindspot · S1 E1 · Pilot", playbackDisplayTitle("Blindspot (2015) - S01E01 - Pilot [1080p]"))
        val episode = com.atlas.atlastv.ui.series.SeriesEpisodeItem("Blindspot - S01E01 - Pilot [1080p]", 1, 1, 55, "mp4", "")
        assertEquals("Pilot", episode.displayEpisodeName("Blindspot (2015) [HD]"))
        assertEquals(55, episode.episodeId)
        assertEquals("Blindspot - S01E01 - Pilot [1080p]", episode.title)
    }
}
