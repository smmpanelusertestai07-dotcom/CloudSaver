package com.pocketide.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietRunsTest {
    private val clock = FakeClock()
    private val accounts = FakeAccounts(clock)
    private val path = claudeTranscript("owner/app", "s1", "c0ffee00-1111")

    private suspend fun syncedPhone(): TestPhone {
        val phone = TestPhone(accounts, clock).apply { sessions += session("s1", at = clock.now, ref = "c0ffee00-1111") }
        phone.homeFile("claude", path).writeText("hi\n")
        phone.engine.syncNow()
        return phone
    }

    private suspend fun TestPhone.periodicRun() = engine.runScheduled(periodic = true) {}

    @Test
    fun anIdlePhonesPeriodicRunStopsBeforeGitHubAndDrive() = runBlocking {
        val phone = syncedPhone()
        val checks = phone.keyringChecks
        // Any call to Drive would now fail the run.
        phone.drive.offline = true

        clock.advance(Durations.HOUR)
        assertEquals(WorkResult.OK, phone.periodicRun())

        assertEquals("no keyring check on GitHub either", checks, phone.keyringChecks)
        assertTrue(phone.engine.status.value is SyncStatus.UpToDate)
    }

    @Test
    fun aPeriodicRunStillSendsWhatChanged() = runBlocking {
        val phone = syncedPhone()
        phone.homeFile("claude", path).appendText("more\n")
        clock.advance(Durations.HOUR)

        phone.periodicRun()

        assertTrue(phone.queued().isEmpty())
        assertEquals(listOf(0L, 3L), phone.remoteIndex()!!.objects.filter { it.path == path }.map { it.offset }.sorted())
    }

    @Test
    fun anIdlePhoneStillAsksDriveEveryFewHoursForOtherPhonesChanges() = runBlocking {
        val phone = syncedPhone()
        phone.rewriteRemoteIndex { index ->
            index.copy(revision = index.revision + 1, sessions = index.sessions.map { it.copy(title = "Renamed on another phone") })
        }

        clock.advance(Durations.HOUR)
        phone.periodicRun()
        assertEquals("Chat s1", phone.sessions.single().title)

        clock.advance(SyncPass.IDLE_PULL_MS)
        phone.periodicRun()
        assertEquals("Renamed on another phone", phone.sessions.single().title)
    }

    @Test
    fun whatWaitsOfflineAsksForASyncAsSoonAsANetworkIsBack() = runBlocking {
        val phone = syncedPhone()
        phone.network.online = false
        phone.homeFile("claude", path).appendText("written offline\n")

        phone.engine.syncNow()

        assertEquals(1, phone.queued().size)
        assertEquals(1, phone.scheduler.whenOnline)
    }
}
