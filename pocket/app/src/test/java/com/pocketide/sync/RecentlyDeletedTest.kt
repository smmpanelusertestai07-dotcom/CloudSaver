package com.pocketide.sync

import com.pocketide.core.Settings
import com.pocketide.model.SessionStatus
import com.pocketide.sessions.Sessions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentlyDeletedTest {
    private val clock = FakeClock()
    private val accounts = FakeAccounts(clock)
    private val day = Durations.DAY

    private fun phone(deviceId: String = "phone-a", settings: Settings = Settings(onboardingDone = true)) =
        TestPhone(accounts, clock, deviceId = deviceId, deviceName = deviceId, settings = settings)

    private fun TestPhone.chat(id: String, text: String) {
        homeFile("claude", claudeTranscript("owner/app", id, "agent-$id")).writeText(text)
        mediaFile("claude", "owner/app", id, "shot-$id.png").writeBytes(text.toByteArray() + byteArrayOf(1, 2, 3))
    }

    private fun TestPhone.markDeleted(id: String, at: Long) {
        val i = sessions.indexOfFirst { it.id == id }
        sessions[i] = sessions[i].copy(status = SessionStatus.DELETED, deletedAt = at)
    }

    @Test
    fun theThirtyDayCountContinuesAfterAReinstallFromTheDateInDrive() = runBlocking {
        val first = phone()
        first.sessions += session("old", at = clock.now, ref = "agent-old")
        first.sessions += session("kept", at = clock.now, ref = "agent-kept")
        first.chat("old", "old chat\n")
        first.chat("kept", "kept chat\n")
        first.engine.syncNow()
        val oldFiles = first.remoteIndex()!!.objects.filter { it.sessionId == "old" }.map { it.name }
        assertEquals(2, oldFiles.size)

        val deletedAt = clock.now
        first.markDeleted("old", deletedAt)
        first.engine.syncNow()
        assertEquals(deletedAt, first.remoteIndex()!!.sessions.single { it.id == "old" }.deletedAt)
        assertTrue("deleting marks the index only: no copy, nothing erased", oldFiles.all { it in first.drive.objectNames() })

        // Reinstalled 20 days later: a new install with no local memory of the deletion.
        clock.advance(20 * day)
        val reinstalled = phone(deviceId = "reinstall")
        assertEquals(WorkResult.OK, reinstalled.engine.runMaintenance())
        assertTrue(oldFiles.all { it in reinstalled.drive.objectNames() })
        val listed = reinstalled.engine.driveSessions.value.single { it.id == "old" }
        assertEquals(deletedAt, listed.deletedAt)
        assertEquals(deletedAt + 30 * day, RecentlyDeleted.erasesAt(listed.deletedAt!!))

        clock.advance(10 * day)
        reinstalled.engine.runMaintenance()
        val index = reinstalled.remoteIndex()!!
        assertTrue(index.sessions.none { it.id == "old" })
        assertTrue(index.objects.none { it.sessionId == "old" })
        assertTrue("erased from Drive for good", oldFiles.none { it in reinstalled.drive.objectNames() })
        assertTrue(index.objects.any { it.sessionId == "kept" })
        assertTrue("old" in reinstalled.erasedSessions)
    }

    @Test
    fun deleteForeverErasesAtTheNextSyncNotTheDailyJob() = runBlocking {
        val phone = phone()
        phone.sessions += session("gone", at = clock.now, ref = "agent-gone")
        phone.chat("gone", "secret plans\n")
        phone.engine.syncNow()
        val files = phone.remoteIndex()!!.objects.filter { it.sessionId == "gone" }.map { it.name }

        phone.markDeleted("gone", Sessions.ERASE_NOW)
        phone.engine.syncNow()

        assertTrue(files.none { it in phone.drive.objectNames() })
        assertTrue(phone.remoteIndex()!!.sessions.none { it.id == "gone" })
        assertEquals(listOf("gone"), phone.erasedSessions)
        assertTrue(phone.sessions.none { it.id == "gone" })

        // Erased everywhere: a copy left on the phone goes too, so it can never be sent again.
        assertFalse(phone.homeFile("claude", claudeTranscript("owner/app", "gone", "agent-gone")).exists())
        assertFalse(phone.mediaFile("claude", "owner/app", "gone", "shot-gone.png").exists())
        assertTrue(phone.state().tracks.values.none { it.sessionId == "gone" })
    }

    @Test
    fun eraseForeverQueuesUntilDriveCanBeReached() = runBlocking {
        val phone = phone()
        phone.sessions += session("a", at = clock.now, ref = "agent-a")
        phone.chat("a", "hello\n")
        phone.engine.syncNow()
        phone.drive.offline = true
        phone.network.online = false
        phone.engine.eraseForever(listOf("a"))
        assertEquals(setOf("a"), phone.state().eraseQueue)

        phone.drive.offline = false
        phone.network.online = true
        phone.engine.syncNow()
        assertTrue(phone.remoteIndex()!!.objects.none { it.sessionId == "a" })
        assertTrue(phone.state().eraseQueue.isEmpty())
    }

    @Test
    fun aChatInRecentlyDeletedStillUploadsWhatWasWaiting() = runBlocking {
        val phone = phone()
        phone.sessions += session("s", at = clock.now, ref = "agent-s")
        phone.chat("s", "one\n")
        phone.engine.syncNow()
        phone.homeFile("claude", claudeTranscript("owner/app", "s", "agent-s")).appendText("two\n")
        phone.markDeleted("s", clock.now)
        clock.advance(Durations.MINUTE)
        phone.engine.uploadNow(listOf("s"))
        val pieces = phone.remoteIndex()!!.objects.filter { it.sessionId == "s" && it.path.endsWith(".jsonl") }
        assertEquals(8L, pieces.sumOf { it.length })
    }

    @Test
    fun oldChatsGetASevenDayNoticeBeforeTheyMoveToRecentlyDeleted() = runBlocking {
        val phone = phone(settings = Settings(onboardingDone = true, keepChatsMonths = 3))
        phone.sessions += session("ancient", at = clock.now - 120 * day)
        phone.sessions += session("fresh", at = clock.now - 10 * day)
        phone.sessions += session("revived", at = clock.now - 100 * day)
        phone.engine.syncNow()

        phone.engine.runMaintenance()
        val notice = phone.notifier.posted.single { it.key == "keep" }
        assertTrue(notice.text.startsWith("2 chats"))
        assertEquals(setOf("ancient", "revived"), phone.state().notices.keys)
        assertTrue(phone.deletedLocally.isEmpty())

        // "revived" is used again during the notice; "ancient" is not.
        clock.advance(3 * day)
        val i = phone.sessions.indexOfFirst { it.id == "revived" }
        phone.sessions[i] = phone.sessions[i].copy(lastActivityAt = clock.now)
        phone.engine.syncNow()
        clock.advance(4 * day)
        phone.engine.runMaintenance()

        assertEquals(listOf("ancient"), phone.deletedLocally)
        val index = phone.remoteIndex()!!
        assertEquals(clock.now, index.sessions.single { it.id == "ancient" }.deletedAt)
        assertEquals(null, index.sessions.single { it.id == "revived" }.deletedAt)
        assertEquals(null, index.sessions.single { it.id == "fresh" }.deletedAt)
        assertTrue(phone.state().notices.isEmpty())
    }

    @Test
    fun whenPocketIdesSpaceIsFullChatsOlderThanAYearAreTrimmedAfterANotice() = runBlocking {
        val phone = phone(settings = Settings(onboardingDone = true, driveLimitGb = 0))
        phone.sessions += session("year-old", at = clock.now - 400 * day)
        phone.sessions += session("recent", at = clock.now - 30 * day)
        phone.engine.syncNow()
        phone.engine.runMaintenance()
        assertTrue(phone.notifier.posted.single { it.key == "trim" }.text.contains("older than 12 months"))
        clock.advance(7 * day)
        phone.engine.runMaintenance()
        assertEquals(listOf("year-old"), phone.deletedLocally)
        assertTrue(phone.remoteIndex()!!.sessions.single { it.id == "year-old" }.deletedAt != null)
    }

    @Test
    fun theTrimRuleIsOffWhenTheOwnerTurnedItOff() = runBlocking {
        val phone = phone(settings = Settings(onboardingDone = true, driveLimitGb = 0, autoTrimOldChats = false))
        phone.sessions += session("year-old", at = clock.now - 400 * day)
        phone.engine.syncNow()
        phone.engine.runMaintenance()
        clock.advance(8 * day)
        phone.engine.runMaintenance()
        assertFalse(phone.notifier.posted.any { it.key == "trim" })
        assertTrue(phone.deletedLocally.isEmpty())
    }

    @Test
    fun retentionPlanIsPureAndKeepsNoticesPerRule() {
        val now = clock.now
        val sessions = listOf(session("a", at = now - 200 * day), session("b", at = now - 10 * day))
        val first = Retention.plan(sessions, emptyMap(), Retention.RULE_KEEP, 6, now)
        assertEquals(listOf("a"), first.noticed)
        assertTrue(first.moveNow.isEmpty())
        val later = Retention.plan(sessions, first.notices, Retention.RULE_KEEP, 6, now + 7 * day)
        assertEquals(listOf("a"), later.moveNow)
        assertTrue(later.notices.isEmpty())
        val forever = Retention.plan(sessions, first.notices, Retention.RULE_KEEP, 0, now + 7 * day)
        assertTrue("\"Until I delete\" cancels pending notices", forever.notices.isEmpty() && forever.moveNow.isEmpty())
    }
}
