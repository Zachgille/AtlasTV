package com.atlas.atlastv.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal fun playbackPreferencesCorruptionHandler() = ReplaceFileCorruptionHandler<Preferences> { emptyPreferences() }
private val Context.playbackDataStore by preferencesDataStore(name = "playback_preferences", corruptionHandler = playbackPreferencesCorruptionHandler())

interface PlaybackPreferencesStore {
    suspend fun loadAutoplay(): Boolean
    suspend fun saveAutoplay(enabled: Boolean)
}

/** Singleton Context delegate keeps exactly one transactional DataStore instance per file. */
class DataStorePlaybackPreferencesStore(private val dataStore: DataStore<Preferences>) : PlaybackPreferencesStore, TrackPreferencesStore {
    override suspend fun loadAutoplay(): Boolean = dataStore.data.first()[AutoplayKey] ?: false
    override suspend fun saveAutoplay(enabled: Boolean) { dataStore.edit { it[AutoplayKey] = enabled } }
    override suspend fun loadTracks(): TrackPreferences {
        val saved = dataStore.data.first()
        return TrackPreferences(
            LanguageTrackPreference(saved[AudioLanguageKey] ?: "en", saved[AudioRoleKey] ?: 0).sanitizedOrNull() ?: LanguageTrackPreference("en"),
            if (saved[SubtitlesEnabledKey] == true) saved[SubtitleLanguageKey]?.let {
                LanguageTrackPreference(it, saved[SubtitleRoleKey] ?: 0, saved[SubtitleForcedKey] ?: false).sanitizedOrNull()
            } else null)
    }
    override suspend fun saveAudio(preference: LanguageTrackPreference) {
        val safe = requireNotNull(preference.sanitizedOrNull())
        dataStore.edit { it[AudioLanguageKey] = safe.language; it[AudioRoleKey] = safe.roleFlags }
    }
    override suspend fun saveSubtitles(preference: LanguageTrackPreference?) {
        val safe = preference?.let { requireNotNull(it.sanitizedOrNull()) }
        dataStore.edit {
            it[SubtitlesEnabledKey] = safe != null
            if (safe == null) { it.remove(SubtitleLanguageKey); it.remove(SubtitleRoleKey); it.remove(SubtitleForcedKey) }
            else { it[SubtitleLanguageKey] = safe.language; it[SubtitleRoleKey] = safe.roleFlags; it[SubtitleForcedKey] = safe.forced }
        }
    }
    companion object {
        private val AutoplayKey = booleanPreferencesKey("autoplay_next_episode")
        private val AudioLanguageKey = stringPreferencesKey("preferred_audio_language")
        private val AudioRoleKey = intPreferencesKey("preferred_audio_role")
        private val SubtitlesEnabledKey = booleanPreferencesKey("preferred_subtitles_enabled")
        private val SubtitleLanguageKey = stringPreferencesKey("preferred_subtitle_language")
        private val SubtitleRoleKey = intPreferencesKey("preferred_subtitle_role")
        private val SubtitleForcedKey = booleanPreferencesKey("preferred_subtitle_forced")
        fun create(context: Context) = DataStorePlaybackPreferencesStore(context.applicationContext.playbackDataStore)
    }
}

data class PlaybackPreferencesState(
    val enabled: Boolean = false,
    val loaded: Boolean = false,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val loadFailed: Boolean = false,
    val saveFailed: Boolean = false,
) {
    val actionLabel: String get() = when {
        loading -> "Loading playback settings…"
        loadFailed -> "Retry playback settings"
        saving -> "Saving autoplay…"
        saveFailed -> "Autoplay: Off · Retry save"
        else -> "Autoplay next episode: ${if (enabled) "On" else "Off"}"
    }
}

/** Enabling requires a durable acknowledgment. Disabling is immediate; failed writes offer explicit retry. */
class PlaybackPreferencesStateHolder(private val store: PlaybackPreferencesStore, private val scope: CoroutineScope) {
    var state by mutableStateOf(PlaybackPreferencesState())
        private set
    private var job: Job? = null
    private var pendingValue: Boolean? = null
    init { reload() }

    fun reload() {
        if (job?.isActive == true) return
        state = PlaybackPreferencesState()
        job = scope.launch {
            try { state = PlaybackPreferencesState(enabled = store.loadAutoplay(), loaded = true, loading = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state = PlaybackPreferencesState(loading = false, loadFailed = true) }
        }
    }

    fun toggleOrRetry() {
        if (state.loading || state.saving) return
        if (!state.loaded) { reload(); return }
        val target = pendingValue ?: !state.enabled
        pendingValue = target
        // Never keep autoplay running after the viewer asks to turn it off.
        state = state.copy(enabled = if (target) state.enabled else false, saving = true, saveFailed = false)
        job = scope.launch {
            try {
                store.saveAutoplay(target)
                pendingValue = null
                state = state.copy(enabled = target, saving = false, saveFailed = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state = state.copy(enabled = false, saving = false, saveFailed = true) }
        }
    }
}
