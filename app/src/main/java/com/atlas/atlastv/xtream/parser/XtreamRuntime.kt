package com.atlas.atlastv.xtream.parser

import com.atlas.atlastv.core.media.MediaRuntime

/** Units are explicit: duration_secs is seconds; duration is an HH:MM:SS clock. */
internal object XtreamRuntime {
    private val secondsPattern = Regex("[0-9]{1,8}(?:\\.[0-9]{1,3})?")
    private val clockPart = Regex("[0-9]{1,2}")
    private fun secondsMillis(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.length <= 12 && it.matches(secondsPattern) } ?: return null
        val parts = text.split('.')
        return parts[0].toLong() * 1000 + (parts.getOrNull(1)?.padEnd(3, '0')?.toLong() ?: 0)
    }
    fun millis(seconds: String?, clock: String?): Long? {
        MediaRuntime.validMillis(secondsMillis(seconds))?.let { return it }
        val parts = clock?.trim()?.takeIf { it.length <= 24 }?.split(':')?.map { it.trim() } ?: return null
        if (parts.size != 3 || !parts[0].matches(clockPart) || !parts[1].matches(clockPart)) return null
        val hours = parts[0].toLong()
        val minutes = parts[1].toLong().takeIf { it < 60 } ?: return null
        val remainder = secondsMillis(parts[2])?.takeIf { it < 60_000 } ?: return null
        return MediaRuntime.validMillis(hours * 3_600_000 + minutes * 60_000 + remainder)
    }
}
