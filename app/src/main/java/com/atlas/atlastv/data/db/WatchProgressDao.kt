package com.atlas.atlastv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface WatchProgressDao {
    @Query("SELECT * FROM watch_progress_records ORDER BY updatedAtMs DESC, contentType ASC, streamId ASC")
    suspend fun records(): List<WatchProgressEntity>

    @Query("DELETE FROM watch_progress_records")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: WatchProgressEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<WatchProgressEntity>)

    @Transaction
    suspend fun replaceAll(records: List<WatchProgressEntity>) {
        clear()
        insertAll(records)
    }

    @Query("SELECT COUNT(*) FROM watch_progress_records")
    suspend fun count(): Int

    @Query("SELECT * FROM watch_progress_records WHERE contentType = :type AND streamId = :id LIMIT 1")
    suspend fun record(type: String, id: Int): WatchProgressEntity?

    @Transaction
    suspend fun upsertIfNewer(value: WatchProgressEntity) {
        val current = record(value.contentType, value.streamId)
        if (current == null || value.updatedAtMs >= current.updatedAtMs) upsert(value)
    }

    @Query("DELETE FROM movie_watchlist_items WHERE streamId = :id AND addedAtMs <= :startedAtMs")
    suspend fun removeStartedMovie(id: Int, startedAtMs: Long)

    @Query("DELETE FROM series_watchlist_items WHERE seriesId = :id AND addedAtMs <= :startedAtMs")
    suspend fun removeStartedShow(id: Int, startedAtMs: Long)

    /** Progress and membership commit together; a later deliberate Add always wins. */
    @Transaction
    suspend fun saveStartedPlayback(value: WatchProgressEntity, startedAtMs: Long) {
        val current = record(value.contentType, value.streamId)
        if (current != null && current.updatedAtMs > value.updatedAtMs) return
        upsert(value)
        val progress = value.toWatchProgressRecordOrNull() ?: return
        if (!com.atlas.atlastv.playback.WatchProgressPolicy.isResumeEligible(progress)) return
        when (progress.contentType) {
            com.atlas.atlastv.playback.WatchProgressContentType.Movie ->
                if (progress.streamId > 0) removeStartedMovie(progress.streamId, startedAtMs)
            com.atlas.atlastv.playback.WatchProgressContentType.SeriesEpisode ->
                progress.seriesId?.takeIf { it > 0 }?.let { removeStartedShow(it, startedAtMs) }
        }
    }

    /** Apply one explicit multi-episode edit atomically, preserving unrelated/newer progress. */
    @Transaction
    suspend fun upsertBatchIfNewer(values: List<WatchProgressEntity>) {
        values.forEach { upsertIfNewer(it) }
    }

    @Transaction
    suspend fun seedIfEmpty(values: List<WatchProgressEntity>) {
        if (count() == 0) insertAll(values)
    }
}
