package com.atlas.atlastv.playback

import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi

/** Re-resolves languages against supported groups. Group IDs belong only to runtime overrides. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackTrackPreferencesController(private val player: Player,
    private val rememberChoice: (PlaybackTrackType, LanguageTrackPreference?) -> Unit = { _, _ -> }) {
    private var preferences = TrackPreferences()
    private var manualAudio = false
    private var manualSubtitles = false
    private var manualAudioPreference: LanguageTrackPreference? = null
    private var manualSubtitlePreference: LanguageTrackPreference? = null
    fun update(value: TrackPreferences) { preferences = value; apply() }
    fun apply() {
        val current = player.trackSelectionParameters
        val builder = current.buildUpon()
        val tracksKnown = player.currentTracks.groups.isNotEmpty()
        if (!manualAudio || tracksKnown && !hasCurrentOverride(C.TRACK_TYPE_AUDIO)) {
            val wanted = manualAudioPreference ?: preferences.audio
            builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO).setPreferredAudioLanguage(wanted.language)
                .setPreferredAudioRoleFlags(wanted.roleFlags)
            preferredTrack(player.currentTracks.playbackTrackOptions(PlaybackTrackType.Audio), wanted)?.let {
                builder.setOverrideForType(TrackSelectionOverride(player.currentTracks.groups[it.groupIndex].mediaTrackGroup, it.trackIndex))
            }
        }
        if (!manualSubtitles || tracksKnown && (manualSubtitlePreference != null || C.TRACK_TYPE_TEXT !in current.disabledTrackTypes) && !hasCurrentOverride(C.TRACK_TYPE_TEXT)) {
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            val wanted = if (manualSubtitles) manualSubtitlePreference else preferences.subtitles
            val preferred = preferredTrack(player.currentTracks.playbackTrackOptions(PlaybackTrackType.Subtitle), wanted)
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, preferred == null)
            preferred?.let { builder.setOverrideForType(TrackSelectionOverride(player.currentTracks.groups[it.groupIndex].mediaTrackGroup, it.trackIndex)) }
        }
        val next = builder.build()
        if (next != current) player.trackSelectionParameters = next
    }
    private fun hasCurrentOverride(type: Int): Boolean = player.trackSelectionParameters.overrides.values.any { selection ->
        selection.type == type && player.currentTracks.groups.any { group -> group.mediaTrackGroup == selection.mediaTrackGroup &&
            selection.trackIndices.all { it in 0 until group.length && group.isTrackSupported(it) } }
    }
    /** null rejects stale/unsupported choices; false means unidentified language, current video only. */
    fun select(option: PlaybackTrackOption): Boolean? {
        val actual = player.currentTracks.playbackTrackOptions(option.type).firstOrNull {
            it.groupIndex == option.groupIndex && it.trackIndex == option.trackIndex && it.label == option.label &&
                it.language == option.language && it.roleFlags == option.roleFlags && it.forced == option.forced
        } ?: return null
        val type = option.type.media3Type()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(player.currentTracks.groups[actual.groupIndex].mediaTrackGroup, actual.trackIndex)).build()
        val preference = normalizedTrackLanguage(actual.language)?.let { LanguageTrackPreference(it, actual.roleFlags, actual.forced) }
        if (option.type == PlaybackTrackType.Audio) { manualAudio = true; manualAudioPreference = preference }
        else { manualSubtitles = true; manualSubtitlePreference = preference }
        if (preference == null) return false
        rememberChoice(option.type, preference)
        return true
    }
    fun subtitlesOff() {
        manualSubtitles = true
        manualSubtitlePreference = null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        rememberChoice(PlaybackTrackType.Subtitle, null)
    }
}

fun PlaybackTrackType.media3Type(): Int = if (this == PlaybackTrackType.Audio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT

@androidx.annotation.OptIn(UnstableApi::class)
fun Tracks.playbackTrackOptions(type: PlaybackTrackType): List<PlaybackTrackOption> = groups.flatMapIndexed { groupIndex, group ->
    if (group.type != type.media3Type()) return@flatMapIndexed emptyList()
    (0 until group.length).filter { group.isTrackSupported(it) }.map { index ->
        val format = group.getTrackFormat(index)
        val forced = type == PlaybackTrackType.Subtitle && format.selectionFlags and C.SELECTION_FLAG_FORCED != 0
        PlaybackTrackOption(type, groupIndex, index,
            playbackTrackLabel(format.language, format.label, "${if (type == PlaybackTrackType.Audio) "Audio" else "Subtitle"} ${index + 1}",
                format.channelCount.takeIf { type == PlaybackTrackType.Audio && it > 0 }, forced),
            group.isTrackSelected(index), normalizedTrackLanguage(format.language), format.roleFlags, forced)
    }
}
