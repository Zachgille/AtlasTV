package com.atlas.atlastv.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchProgressStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun storesOnlySafeProgressData() {
        val file = temporaryFolder.newFile("watch_progress.json")
        val store = WatchProgressStore(file)

        store.saveRecord(
            WatchProgressRecord(
                contentType = WatchProgressContentType.Movie,
                streamId = 2001,
                title = "http://example.test/movie/fake_user/fake_pass/2001.mp4",
                categoryName = "Fixture Action",
                extension = "mp4",
                durationMs = 7_200_000L,
                positionMs = 900_000L,
                updatedAtMs = 10L,
            ),
        )

        val json = file.readText(Charsets.UTF_8)
        assertFalse(json.contains("example.test"))
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains("/movie/"))
        assertEquals("Untitled", store.load().records.single().title)
    }

    @Test
    fun resumeEligibilityRejectsStartAndCompletedAndAcceptsMidTitle() {
        val nearStart = progress(positionMs = 10_000L, durationMs = 7_200_000L)
        val midTitle = progress(positionMs = 900_000L, durationMs = 7_200_000L)
        val nearEnd = progress(positionMs = 7_100_000L, durationMs = 7_200_000L, completed = true)
        val invalidDuration = progress(positionMs = 900_000L, durationMs = -1L)

        assertFalse(WatchProgressPolicy.isResumeEligible(nearStart))
        assertTrue(WatchProgressPolicy.isResumeEligible(midTitle))
        assertFalse(WatchProgressPolicy.isResumeEligible(nearEnd))
        assertFalse(WatchProgressPolicy.isResumeEligible(invalidDuration))
        assertNull(WatchProgressPolicy.resumePositionMs(nearStart))
        assertEquals(900_000L, WatchProgressPolicy.resumePositionMs(midTitle))
    }

    @Test
    fun detectsCompletionNearEnd() {
        assertTrue(WatchProgressPolicy.isCompleted(9_300L, 10_000L))
        assertTrue(WatchProgressPolicy.isCompleted(7_100_000L, 7_200_000L))
        assertFalse(WatchProgressPolicy.isCompleted(900_000L, 7_200_000L))
    }

    @Test fun shortVideoIsNotCompletedAtItsStartAndCanResumeInItsMiddle() {
        assertFalse(WatchProgressPolicy.isCompleted(0L, 60_000L))
        assertFalse(WatchProgressPolicy.isCompleted(35_000L, 60_000L))
        assertTrue(WatchProgressPolicy.isResumeEligible(progress(positionMs = 35_000L, durationMs = 60_000L)))
        assertTrue(WatchProgressPolicy.isCompleted(56_000L, 60_000L))
    }

    @Test fun olderCheckpointCannotEraseNewerRecordAndRewindToStartReplacesIt() {
        val store = WatchProgressStore(temporaryFolder.newFile("progress.json"))
        store.saveRecord(progress(positionMs = 300_000L, updatedAtMs = 30L))
        store.saveRecord(progress(positionMs = 400_000L, updatedAtMs = 20L))
        assertEquals(300_000L, store.load().records.single().positionMs)
        store.saveRecord(progress(positionMs = 0L, updatedAtMs = 40L))
        assertEquals(0L, store.load().records.single().positionMs)
        assertTrue(store.load().continueWatching().isEmpty())
        assertFalse(java.io.File(temporaryFolder.root, "progress.json.pending").exists())
    }

    @Test fun malformedNonemptyStorageFailsInsteadOfPretendingProgressIsEmpty() {
        val file = temporaryFolder.newFile("progress.json")
        file.writeText("{invalid")
        val failure = runCatching { WatchProgressStore(file).load() }
        assertTrue(failure.isFailure)
        assertEquals("{invalid", file.readText())
    }

    @Test fun interruptedExportDoesNotReplaceLastGoodFile() {
        val file = temporaryFolder.newFile("progress.json")
        val store = WatchProgressStore(file)
        store.saveRecord(progress(updatedAtMs = 30))
        java.io.File(temporaryFolder.root, "progress.json.pending").writeText("{interrupted")
        assertEquals(30L, WatchProgressStore(file).load().records.single().updatedAtMs)
        store.saveRecord(progress(updatedAtMs = 40))
        assertEquals(40L, WatchProgressStore(file).load().records.single().updatedAtMs)
    }

    @Test
    fun continueWatchingOrdersByUpdatedAtAndHidesCompleted() {
        val snapshot = WatchProgressSnapshot(
            records = listOf(
                progress(streamId = 1, updatedAtMs = 100L),
                progress(streamId = 2, updatedAtMs = 300L),
                progress(streamId = 3, updatedAtMs = 200L, completed = true),
            ),
        )

        assertEquals(listOf(2, 1), snapshot.continueWatching().map { it.streamId })
    }

    private fun progress(
        streamId: Int = 2001,
        positionMs: Long = 900_000L,
        durationMs: Long? = 7_200_000L,
        updatedAtMs: Long = 10L,
        completed: Boolean = false,
    ): WatchProgressRecord =
        WatchProgressRecord(
            contentType = WatchProgressContentType.Movie,
            streamId = streamId,
            title = "Fixture Movie",
            categoryName = "Fixture",
            extension = "mp4",
            durationMs = durationMs,
            positionMs = positionMs,
            updatedAtMs = updatedAtMs,
            completed = completed,
        )
}
