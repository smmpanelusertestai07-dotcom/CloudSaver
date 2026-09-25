package com.pocketide.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android often starts the process only to run a sync or the daily job: nothing is loaded yet. */
class ColdStartTest {
    private val clock = FakeClock()
    private val accounts = FakeAccounts(clock)

    @Test
    fun aChatKeptOffDriveStaysOffWhenTheSyncStartsTheApp() = runBlocking {
        val phone = TestPhone(accounts, clock)
        phone.sessions += session("private", at = clock.now, backUp = false, ref = "feedface-2222")
        phone.homeFile("claude", claudeTranscript("owner/app", "private", "feedface-2222")).writeText("keep me here\n")
        phone.mediaFile("claude", "owner/app", "private", "shot.png").writeBytes(byteArrayOf(1, 2, 3))
        phone.coldStart = true

        phone.engine.runScheduled {}

        val objects = phone.remoteIndex()?.objects.orEmpty()
        assertTrue(objects.none { it.path.contains("feedface-2222") || it.path.endsWith("shot.png") })
        assertTrue(phone.queued().none { it.path.contains("feedface-2222") || it.path.endsWith("shot.png") })
    }

    @Test
    fun theOpenChatKeepsItsPhoneCopyWhenTheDailyJobStartsTheApp() = runBlocking {
        val phone = TestPhone(accounts, clock)
        phone.sessions += session("open", at = clock.now, ref = "c0ffee00-1111")
        phone.active += "open"
        val transcript = phone.homeFile("claude", claudeTranscript("owner/app", "open", "c0ffee00-1111")).apply { writeText("in Drive\n") }
        phone.engine.syncNow()

        clock.advance(31 * Durations.DAY)
        phone.coldStart = true
        phone.engine.runMaintenance()

        assertTrue("a session open in a room keeps its phone copy", transcript.exists())
    }

    @Test
    fun filesOfASessionThisPhoneDoesNotKnowWait() {
        val book = SessionBook(listOf(session("known", at = clock.now)), emptyList(), emptySet())
        assertTrue(book.uploadable("known"))
        assertTrue("room-level files", book.uploadable(null))
        assertFalse(book.uploadable("unknown"))
    }
}
