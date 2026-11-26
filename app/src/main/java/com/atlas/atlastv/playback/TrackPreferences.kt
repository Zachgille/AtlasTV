package com.atlas.atlastv.playback

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

data class LanguageTrackPreference(val language: String, val roleFlags: Int = 0, val forced: Boolean = false)
data class TrackPreferences(val audio: LanguageTrackPreference = LanguageTrackPreference("en"), val subtitles: LanguageTrackPreference? = null)

fun LanguageTrackPreference.sanitizedOrNull(): LanguageTrackPreference? =
    normalizedTrackLanguage(language)?.let { copy(language = it, roleFlags = roleFlags.coerceIn(0, 65535)) }

/** Track group/index/labels belong to one video and never enter durable preferences. */
fun preferredTrack(options: List<PlaybackTrackOption>, preference: LanguageTrackPreference?): PlaybackTrackOption? {
    val wanted = preference?.sanitizedOrNull() ?: return null
    return options.filter { normalizedTrackLanguage(it.language)?.substringBefore('-') == wanted.language.substringBefore('-') }
        .minWithOrNull(compareBy<PlaybackTrackOption> { if (normalizedTrackLanguage(it.language) == wanted.language) 0 else 1 }
            .thenBy { if (it.forced == wanted.forced) 0 else 1 }
            .thenBy { if (it.roleFlags == wanted.roleFlags) 0 else 1 }
            .thenBy { Integer.bitCount(it.roleFlags xor wanted.roleFlags) }
            .thenBy { it.groupIndex }.thenBy { it.trackIndex })
}

interface TrackPreferencesStore {
    suspend fun loadTracks(): TrackPreferences
    suspend fun saveAudio(preference: LanguageTrackPreference)
    suspend fun saveSubtitles(preference: LanguageTrackPreference?)
}

data class TrackPreferencesState(val preferences: TrackPreferences = TrackPreferences(), val loading: Boolean = true,
    val loadFailed: Boolean = false, val saving: Boolean = false, val saveFailed: Boolean = false) {
    val errorLabel: String? get() = when {
        saveFailed -> "Couldn't remember your language choice."
        loadFailed -> "Couldn't load saved language choices."
        else -> null
    }
}

/** Field-specific, ordered writes outlive a playback composition. Late reads cannot undo a manual choice. */
class TrackPreferencesStateHolder(private val store: TrackPreferencesStore, private val scope: CoroutineScope) {
    var state by mutableStateOf(TrackPreferencesState()); private set
    private data class Write(val type: PlaybackTrackType, val preference: LanguageTrackPreference?, val version: Long)
    private val versions = mutableMapOf(PlaybackTrackType.Audio to 0L, PlaybackTrackType.Subtitle to 0L)
    private val failed = mutableMapOf<PlaybackTrackType, Write>()
    private val writes = Channel<Write>(Channel.UNLIMITED)
    private var pending = 0
    private var loadJob: Job? = null
    init {
        reload()
        scope.launch {
            for (write in writes) {
                try {
                    if (versions[write.type] != write.version) continue
                    when (write.type) {
                        PlaybackTrackType.Audio -> store.saveAudio(write.preference!!)
                        PlaybackTrackType.Subtitle -> store.saveSubtitles(write.preference)
                    }
                    if (versions[write.type] == write.version) failed.remove(write.type)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (versions[write.type] == write.version) failed[write.type] = write }
                finally { pending--; state = state.copy(saving = pending > 0, saveFailed = failed.isNotEmpty()) }
            }
        }
    }
    fun reload() {
        if (loadJob?.isActive == true) return
        state = state.copy(loading = true, loadFailed = false)
        loadJob = scope.launch {
            try {
                val loaded = store.loadTracks()
                val current = state.preferences
                state = state.copy(loading = false, loadFailed = false, preferences = TrackPreferences(
                    if (versions[PlaybackTrackType.Audio] == 0L) loaded.audio.sanitizedOrNull() ?: LanguageTrackPreference("en") else current.audio,
                    if (versions[PlaybackTrackType.Subtitle] == 0L) loaded.subtitles?.sanitizedOrNull() else current.subtitles))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state = state.copy(loading = false, loadFailed = true) }
        }
    }
    fun choose(type: PlaybackTrackType, preference: LanguageTrackPreference?): Boolean {
        val safe = preference?.sanitizedOrNull()
        if (preference != null && safe == null || type == PlaybackTrackType.Audio && safe == null) return false
        val current = state.preferences
        state = state.copy(preferences = if (type == PlaybackTrackType.Audio) current.copy(audio = safe!!) else current.copy(subtitles = safe))
        val version = versions.getValue(type) + 1; versions[type] = version
        failed.remove(type)
        enqueue(Write(type, safe, version)); return true
    }
    private fun enqueue(write: Write) {
        pending++; state = state.copy(saving = true, saveFailed = failed.isNotEmpty()); writes.trySend(write)
    }
    fun retry() {
        if (state.loadFailed) reload()
        val retry = failed.values.toList(); failed.clear()
        retry.forEach(::enqueue)
        state = state.copy(saveFailed = false)
    }
}
