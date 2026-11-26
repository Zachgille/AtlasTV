package com.atlas.atlastv.data.db

import androidx.room.Entity
import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord

@Entity(
    tableName = "watch_progress_records",
    primaryKeys = ["contentType", "streamId"],
)
data class WatchProgressEntity(
    val contentType: String,
    val streamId: Int,
    val seriesId: Int?,
    val title: String,
    val seriesTitle: String?,
    val episodeTitle: String?,
    val categoryName: String?,
    val extension: String?,
    val durationMs: Long?,
    val positionMs: Long,
    val updatedAtMs: Long,
    val completed: Boolean,
)

fun WatchProgressRecord.toWatchProgressEntity(): WatchProgressEntity {
    val safe = sanitizedWatchProgressRecord()
    return WatchProgressEntity(
        contentType = safe.contentType.storageKey,
        streamId = safe.streamId,
        seriesId = safe.seriesId,
        title = safe.title,
        seriesTitle = safe.seriesTitle,
        episodeTitle = safe.episodeTitle,
        categoryName = safe.categoryName,
        extension = safe.extension,
        durationMs = safe.durationMs,
        positionMs = safe.positionMs,
        updatedAtMs = safe.updatedAtMs,
        completed = safe.completed,
    )
}

fun WatchProgressEntity.toWatchProgressRecordOrNull(): WatchProgressRecord? {
    val type = WatchProgressContentType.fromStorageKey(contentType) ?: return null
    return WatchProgressRecord(
        contentType = type,
        streamId = streamId,
        seriesId = seriesId,
        title = title.safeProgressText("Untitled"),
        seriesTitle = seriesTitle?.safeProgressText("Series"),
        episodeTitle = episodeTitle?.safeProgressText("Episode"),
        categoryName = categoryName?.safeProgressText("Uncategorized"),
        extension = extension?.trim()?.trimStart('.')?.take(12),
        durationMs = durationMs?.takeIf { it > 0L },
        positionMs = positionMs.coerceAtLeast(0L),
        updatedAtMs = updatedAtMs,
        completed = completed,
    )
}

fun WatchProgressRecord.sanitizedWatchProgressRecord(): WatchProgressRecord =
    copy(
        title = title.safeProgressText("Untitled"),
        seriesTitle = seriesTitle?.safeProgressText("Series"),
        episodeTitle = episodeTitle?.safeProgressText("Episode"),
        categoryName = categoryName?.safeProgressText("Uncategorized"),
        extension = extension?.trim()?.trimStart('.')?.take(12),
        positionMs = positionMs.coerceAtLeast(0L),
        durationMs = durationMs?.takeIf { it > 0L },
    )

fun String.safeProgressText(defaultValue: String): String =
    trim()
        .take(160)
        .takeIf { text ->
            text.isNotBlank() &&
                !text.contains("://") &&
                !text.contains("/live/", ignoreCase = true) &&
                !text.contains("/movie/", ignoreCase = true) &&
                !text.contains("/series/", ignoreCase = true)
        }
        ?: defaultValue
