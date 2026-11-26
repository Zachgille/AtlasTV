package com.atlas.atlastv.playback

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackPreferencesTest {
    private class Store : TrackPreferencesStore {
        var value = TrackPreferences()
        var loadDelay = 0L; var saveDelay = 0L; var failLoad = false; var failSave = false
        val writes = mutableListOf<String>()
        override suspend fun loadTracks(): TrackPreferences { delay(loadDelay); if (failLoad) error("private diagnostic"); return value }
        override suspend fun saveAudio(preference: LanguageTrackPreference) { writes += "audio:${preference.language}"; delay(saveDelay); if (failSave) error("private diagnostic"); value = value.copy(audio = preference) }
        override suspend fun saveSubtitles(preference: LanguageTrackPreference?) { writes += "sub:${preference?.language}"; delay(saveDelay); if (failSave) error("private diagnostic"); value = value.copy(subtitles = preference) }
    }
    private fun option(group: Int, language: String?, role: Int = 0, forced: Boolean = false) = PlaybackTrackOption(
        PlaybackTrackType.Subtitle, group, 0, "track", false, language, role, forced)
    @Test fun languagesNormalizeIsoTagsAndRegionsWithoutGuessingFromLabels() {
        assertEquals("en", normalizedTrackLanguage(" ENG ")); assertEquals("en-gb", normalizedTrackLanguage("ENG_GB"))
        assertEquals("fr", normalizedTrackLanguage("fra"))
        for (invalid in listOf(null, "", "und", "zxx", "English", "http://private/en", "eng/password", "xyz")) assertNull(normalizedTrackLanguage(invalid))
    }
    @Test fun reorderedGroupsResolveLanguageRoleAndForcedPreferenceRatherThanSavedIndexes() {
        val options = listOf(option(4, "fr"), option(8, "eng", 0, true), option(12, "en", 0, false), option(2, "en", 8, false))
        assertEquals(12, preferredTrack(options, LanguageTrackPreference("en"))?.groupIndex)
        assertEquals(2, preferredTrack(options.reversed(), LanguageTrackPreference("en", 8))?.groupIndex)
        assertEquals(8, preferredTrack(options, LanguageTrackPreference("en", forced = true))?.groupIndex)
        assertEquals(4, preferredTrack(options, LanguageTrackPreference("fr"))?.groupIndex)
    }
    @Test fun exactRegionWinsAndMissingPreferredLanguageDoesNotInventAMatch() {
        val options = listOf(option(1, "en-us"), option(2, "en-gb"), option(3, null))
        assertEquals(2, preferredTrack(options, LanguageTrackPreference("en-gb"))?.groupIndex)
        assertEquals(1, preferredTrack(options, LanguageTrackPreference("eng"))?.groupIndex)
        assertNull(preferredTrack(options, LanguageTrackPreference("fr"))); assertNull(preferredTrack(options, null))
    }
    @Test fun slowReadDoesNotGatePlaybackAndLateReadMergesUntouchedFields() = runTest {
        val store = Store().apply { loadDelay = 1_000; value = TrackPreferences(LanguageTrackPreference("de"), LanguageTrackPreference("fr")) }
        val holder = TrackPreferencesStateHolder(store, backgroundScope); runCurrent()
        assertEquals("en", holder.state.preferences.audio.language); assertTrue(holder.state.loading)
        holder.choose(PlaybackTrackType.Audio, LanguageTrackPreference("en-gb")); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals("en-gb", holder.state.preferences.audio.language); assertEquals("fr", holder.state.preferences.subtitles?.language)
        assertFalse(holder.state.loading)
    }
    @Test fun subtitleOffBeforeLateReadCannotBeReenabledByTheOldSavedChoice() = runTest {
        val store = Store().apply { loadDelay = 1_000; value = TrackPreferences(subtitles = LanguageTrackPreference("en")) }
        val holder = TrackPreferencesStateHolder(store, backgroundScope); runCurrent()
        holder.choose(PlaybackTrackType.Subtitle, null); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertNull(holder.state.preferences.subtitles); assertNull(store.value.subtitles)
    }
    @Test fun rapidAudioChoicesAndSubtitleChangesRemainOrderedAndFieldIsolated() = runTest {
        val store = Store().apply { saveDelay = 1_000 }
        val holder = TrackPreferencesStateHolder(store, backgroundScope); runCurrent()
        holder.choose(PlaybackTrackType.Audio, LanguageTrackPreference("fr")); runCurrent()
        holder.choose(PlaybackTrackType.Subtitle, LanguageTrackPreference("en"))
        holder.choose(PlaybackTrackType.Audio, LanguageTrackPreference("de"))
        holder.choose(PlaybackTrackType.Audio, LanguageTrackPreference("en"))
        repeat(4) { advanceTimeBy(1_000); runCurrent() }
        assertEquals(listOf("audio:fr", "sub:en", "audio:en"), store.writes)
        assertEquals(TrackPreferences(LanguageTrackPreference("en"), LanguageTrackPreference("en")), store.value)
        assertFalse(holder.state.saving); assertFalse(holder.state.saveFailed)
    }
    @Test fun failedWritesRetainIntentAndRetryIsSafeAndCannotDuplicatePendingWrites() = runTest {
        val store = Store().apply { failSave = true }
        val holder = TrackPreferencesStateHolder(store, backgroundScope); runCurrent()
        holder.choose(PlaybackTrackType.Audio, LanguageTrackPreference("fr")); holder.choose(PlaybackTrackType.Subtitle, LanguageTrackPreference("en")); runCurrent()
        assertTrue(holder.state.saveFailed); assertFalse(holder.state.errorLabel!!.contains("private"))
        assertEquals("fr", holder.state.preferences.audio.language)
        store.failSave = false; holder.retry(); holder.retry(); runCurrent()
        assertEquals(4, store.writes.size); assertFalse(holder.state.saveFailed)
        assertEquals(holder.state.preferences, store.value)
    }
    @Test fun newerChoiceSupersedesFailedIntentAndReopeningLoadsDurableFields() = runTest {
        val store = Store().apply { failSave = true }
        val holder = TrackPreferencesStateHolder(store, backgroundScope); runCurrent()
        holder.choose(PlaybackTrackType.Subtitle, LanguageTrackPreference("fr")); runCurrent()
        store.failSave = false; holder.choose(PlaybackTrackType.Subtitle, null); holder.retry(); runCurrent()
        assertEquals(listOf("sub:fr", "sub:null"), store.writes)
        val reopened = TrackPreferencesStateHolder(store, backgroundScope); runCurrent()
        assertNull(reopened.state.preferences.subtitles); assertFalse(reopened.state.saveFailed)
    }
    @Test fun failedLoadRetriesWithoutUndoingCurrentManualSelection() = runTest {
        val store = Store().apply { failLoad = true; value = TrackPreferences(LanguageTrackPreference("de"), LanguageTrackPreference("fr")) }
        val holder = TrackPreferencesStateHolder(store, backgroundScope); runCurrent(); assertTrue(holder.state.loadFailed)
        holder.choose(PlaybackTrackType.Audio, LanguageTrackPreference("en")); runCurrent()
        store.failLoad = false; holder.retry(); runCurrent()
        assertEquals("en", holder.state.preferences.audio.language); assertEquals("fr", holder.state.preferences.subtitles?.language)
        assertFalse(holder.state.loadFailed)
    }
    @Test fun invalidPersistedValuesAndUnidentifiedSelectionsCannotWriteProviderText() = runTest {
        val store = Store().apply { value = TrackPreferences(LanguageTrackPreference("private://secret"), LanguageTrackPreference("und")) }
        val holder = TrackPreferencesStateHolder(store, backgroundScope); runCurrent()
        assertEquals(TrackPreferences(), holder.state.preferences)
        assertFalse(holder.choose(PlaybackTrackType.Audio, LanguageTrackPreference("English")))
        assertFalse(holder.choose(PlaybackTrackType.Audio, null)); assertTrue(store.writes.isEmpty())
    }
}
