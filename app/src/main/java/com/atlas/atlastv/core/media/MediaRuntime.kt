package com.atlas.atlastv.core.media

/** Optional descriptive runtimes, never playback/completion policy. Unknown stays absent. */
object MediaRuntime {
    const val MaxMillis = 86_400_000L
    fun validMillis(value: Long?): Long? = value?.takeIf { it in 1..MaxMillis }
    fun validMinutes(value: Int?): Int? = value?.takeIf { it in 1..1440 }
    fun minutes(value: Long?): Int? = validMillis(value)?.let { ((it + 59_999) / 60_000).toInt() }
    fun labelMinutes(value: Int?): String? = validMinutes(value)?.let {
        when {
            it < 60 -> "${it}m"
            it % 60 == 0 -> "${it / 60}h"
            else -> "${it / 60}h ${it % 60}m"
        }
    }
    fun labelMillis(value: Long?): String? = labelMinutes(minutes(value))
}
