package com.atlas.atlastv.playback

import java.util.Locale

private val isoLanguages = Locale.getISOLanguages().associateBy { Locale(it).isO3Language }
private val shortLanguageCodes = isoLanguages.values.toSet()

data class PlaybackTrackOption(
    val type: PlaybackTrackType,
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val selected: Boolean,
    val language: String? = null,
    val roleFlags: Int = 0,
    val forced: Boolean = false,
)

enum class PlaybackTrackType {
    Audio,
    Subtitle,
}

/** Accept only known language tags; labels, URLs and undefined metadata are never guessed. */
fun normalizedTrackLanguage(value: String?): String? {
    val code = value?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-') ?: return null
    if (!code.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})*"))) return null
    val primary = code.substringBefore('-')
    // Android's locale inventory can include these sentinel codes; they are not languages.
    if (primary in setOf("und", "zxx")) return null
    val canonical = isoLanguages[primary] ?: primary.takeIf(shortLanguageCodes::contains) ?: return null
    return canonical + code.removePrefix(primary)
}

fun seekTargetMs(currentPositionMs: Long, deltaMs: Long, durationMs: Long?): Long {
    val target = currentPositionMs + deltaMs
    val boundedHigh = durationMs?.takeIf { it > 0L }
    return when {
        boundedHigh != null -> target.coerceIn(0L, boundedHigh)
        else -> target.coerceAtLeast(0L)
    }
}

fun playbackTrackLabel(language: String?, label: String?, fallback: String, channelCount: Int? = null, forced: Boolean = false): String {
    val code = language?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-')
    val languageName = code?.takeIf { it !in setOf("", "und", "zxx") }?.let {
        val primary = it.substringBefore('-')
        val normalized = isoLanguages[primary] ?: primary.takeIf(shortLanguageCodes::contains)
        normalized?.let { value -> Locale.forLanguageTag(value).getDisplayLanguage(Locale.ENGLISH) }
    }
    val parts = listOfNotNull(
        languageName,
        label?.trim()?.takeIf { it.isNotBlank() },
    ).distinctBy { it.lowercase(Locale.ROOT) }
    val description = listOfNotNull(when (channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null }, if (forced) "Forced" else null)
    return (parts.ifEmpty { listOf(fallback) } + description).joinToString(" · ")
}

/** Up opens the menu only from normal playback; an open menu owns its own directional keys. */
fun shouldOpenTrackMenu(menuOpen: Boolean, audioCount: Int, subtitleCount: Int): Boolean =
    !menuOpen && shouldShowTrackOptions(audioCount, subtitleCount)

fun shouldShowTrackOptions(audioCount: Int, subtitleCount: Int): Boolean =
    audioCount > 1 || subtitleCount > 0

fun shouldDirectionalSeek(trackOptionsVisible: Boolean, isSeekable: Boolean): Boolean =
    isSeekable && !trackOptionsVisible

fun shouldShowNoFirstFrameTimeout(
    elapsedMs: Long,
    durationKnown: Boolean,
    firstFrameRendered: Boolean,
    playbackErrorPresent: Boolean,
    timeoutMs: Long = DefaultNoFirstFrameTimeoutMs,
): Boolean =
    elapsedMs >= timeoutMs && durationKnown && !firstFrameRendered && !playbackErrorPresent

const val DefaultNoFirstFrameTimeoutMs: Long = 15_000L
