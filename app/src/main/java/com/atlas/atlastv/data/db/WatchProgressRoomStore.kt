package com.atlas.atlastv.data.db

import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.playback.WatchProgressSnapshot

class WatchProgressRoomStore(
    private val dao: WatchProgressDao,
) {
    suspend fun load(): WatchProgressSnapshot =
        WatchProgressSnapshot(dao.records().mapNotNull { it.toWatchProgressRecordOrNull() })

    suspend fun saveRecord(record: WatchProgressRecord) {
        dao.upsertIfNewer(record.toWatchProgressEntity())
    }

    suspend fun saveStartedPlayback(record: WatchProgressRecord, startedAtMs: Long) {
        dao.saveStartedPlayback(record.toWatchProgressEntity(), startedAtMs)
    }

    suspend fun saveBatch(records: List<WatchProgressRecord>) {
        dao.upsertBatchIfNewer(records.map { it.toWatchProgressEntity() })
    }

    suspend fun saveRecords(records: List<WatchProgressRecord>) {
        dao.replaceAll(records.map { it.toWatchProgressEntity() }.sortedByDescending { it.updatedAtMs })
    }

    suspend fun clear() {
        dao.clear()
    }

    suspend fun count(): Int =
        dao.count()

    suspend fun seedIfEmpty(records: List<WatchProgressRecord>) {
        dao.seedIfEmpty(records.map { it.toWatchProgressEntity() })
    }
}
