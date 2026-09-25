package com.pocketide.sync

import com.pocketide.core.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageFullTest {
    private val clock = FakeClock()
    private val accounts = FakeAccounts(clock)
    private val path = claudeTranscript("owner/app", "s1", "c0ffee00-1111")

    private fun phone(settings: Settings = Settings(onboardingDone = true)) =
        TestPhone(accounts, clock, settings = settings).apply { sessions += session("s1", at = clock.now, ref = "c0ffee00-1111") }

    @Test
    fun googleStorageFullWaitsOnThePhoneAndLocksAfter24Hours() = runBlocking {
        val phone = phone()
        val file = phone.homeFile("claude", path)
        file.writeText("start\n")
        phone.engine.syncNow()
        phone.drive.quotaBytes = phone.drive.usedBytes()

        file.appendText("x".repeat(2_000) + "\n")
        clock.advance(Durations.MINUTE)
        phone.engine.syncNow()
        val since = clock.now
        val waiting = phone.engine.status.value as SyncStatus.Waiting
        assertTrue(waiting.googleStorageFull)
        assertEquals(since, waiting.since)
        assertEquals(2_001L, waiting.pendingBytes)
        assertFalse(waiting.locks)
        assertEquals(Plain.GOOGLE_FULL, waiting.why)
        assertTrue(phone.notifier.posted.any { it.key == "google-full" })
        assertEquals(listOf(PendingUpload("s1", "Chat s1", 2_001, 0)), phone.engine.waiting.value)
        assertEquals(BackupState.WAITING, phone.engine.backups.value.getValue("s1").state)

        clock.advance(23 * Durations.HOUR)
        phone.engine.syncNow()
        assertFalse((phone.engine.status.value as SyncStatus.Waiting).locks)

        clock.advance(Durations.HOUR)
        phone.engine.syncNow()
        val later = phone.engine.status.value as SyncStatus.Waiting
        assertEquals("the wait counts from the first failure", since, later.since)
        assertTrue(later.locks)

        phone.drive.quotaBytes = Long.MAX_VALUE
        clock.advance(Durations.MINUTE)
        phone.engine.syncNow()
        assertTrue(phone.engine.status.value is SyncStatus.UpToDate)
        assertTrue(phone.engine.waiting.value.isEmpty())
        assertEquals(BackupState.BACKED_UP, phone.engine.backups.value.getValue("s1").state)
        assertEquals(clock.now, phone.engine.backups.value.getValue("s1").lastBackedUpAt)
        assertEquals(file.length(), phone.state().tracks.values.single { it.path == path }.syncedLength)
    }

    @Test
    fun aFullDriveThatRefusesEvenTheIndexStartsTheWaitToo() = runBlocking {
        val phone = phone()
        phone.homeFile("claude", path).writeText("start\n")
        phone.engine.syncNow()
        phone.drive.quotaBytes = phone.drive.usedBytes()
        phone.sessions += session("s2", at = clock.now).copy(title = "A new chat with a long title that makes the index grow")
        clock.advance(Durations.MINUTE)
        phone.engine.syncNow()
        val waiting = phone.engine.status.value as SyncStatus.Waiting
        assertTrue(waiting.googleStorageFull)
        assertEquals(clock.now, waiting.since)
        assertEquals(clock.now, phone.state().waiting?.since)
    }

    @Test
    fun twoHundredMegabytesWaitingLocksAtOnce() {
        val mark = WaitingMark(since = clock.now, googleFull = true)
        assertFalse(Limits.locks(mark, 200 * MeteredDataBudget.MB - 1, clock.now))
        assertTrue(Limits.locks(mark, 200 * MeteredDataBudget.MB, clock.now))
        assertTrue(Limits.locks(mark, 0, clock.now + 24 * Durations.HOUR))
    }

    @Test
    fun pocketIdesOwnShareFullIsToldApart() = runBlocking {
        val phone = phone(Settings(onboardingDone = true, driveLimitGb = 0))
        phone.homeFile("claude", path).writeText("hello\n")
        phone.engine.syncNow()
        val waiting = phone.engine.status.value as SyncStatus.Waiting
        assertFalse(waiting.googleStorageFull)
        assertEquals(Plain.SHARE_FULL, waiting.why)
        assertTrue(phone.drive.objectNames().isEmpty())
        assertTrue(phone.notifier.posted.any { it.key == "share-full" })
        assertTrue(phone.engine.storage.value.driveShareFull)
    }

    @Test
    fun aChatMarkedDontBackUpIsNeverUploaded() = runBlocking {
        val phone = phone()
        phone.sessions += session("private", at = clock.now, backUp = false, ref = "feedface-2222")
        phone.homeFile("claude", claudeTranscript("owner/app", "private", "feedface-2222")).writeText("keep me here\n")
        phone.mediaFile("claude", "owner/app", "private", "shot.png").writeBytes(byteArrayOf(1, 2, 3))
        phone.engine.syncNow()
        phone.engine.uploadNow(listOf("private"))
        val index = phone.remoteIndex()!!
        assertTrue(index.objects.none { it.sessionId == "private" })
        assertTrue(index.sessions.none { it.id == "private" })
        assertTrue(phone.queued().none { it.sessionId == "private" })
        assertEquals(BackupState.NOT_BACKED_UP, phone.engine.backups.value.getValue("private").state)
    }

    @Test
    fun videosWaitForWifiUnlessTheOwnerSaysOtherwise() = runBlocking {
        val phone = phone()
        phone.network.metered = true
        phone.mediaFile("claude", "owner/app", "s1", "shot.png").writeBytes(ByteArray(100) { 1 })
        phone.mediaFile("claude", "owner/app", "s1", "run.mp4").writeBytes(ByteArray(300) { 2 })
        phone.engine.syncNow()

        val media = phone.remoteIndex()!!.objects.filter { it.kind == com.pocketide.model.ObjectKind.MEDIA }
        assertEquals(listOf("shot.png"), media.map { it.path.substringAfterLast('/') })
        assertEquals(listOf(PendingUpload("s1", "Chat s1", 300, 1)), phone.engine.waiting.value)
        val chip = phone.engine.backups.value.getValue("s1")
        assertEquals(BackupState.WAITING_FOR_WIFI, chip.state)
        assertEquals(1, chip.videosWaitingForWifi)
        assertTrue("counted as mobile data", phone.engine.usage.value.todayMeteredBytes > 0)

        phone.engine.uploadNow(listOf("s1"))
        assertEquals(2, phone.remoteIndex()!!.objects.count { it.kind == com.pocketide.model.ObjectKind.MEDIA })
        assertTrue(phone.engine.waiting.value.isEmpty())
    }

    @Test
    fun theDailyMobileLimitHoldsSyncBackUntilWifi() = runBlocking {
        val phone = phone(Settings(onboardingDone = true, mobileDailyLimitMb = 0))
        phone.network.metered = true
        phone.homeFile("claude", path).writeText("hello\n")
        phone.engine.syncNow()
        assertTrue(phone.remoteIndex()?.objects.orEmpty().isEmpty())
        assertTrue((phone.engine.status.value as SyncStatus.Error).why.contains("Wi-Fi"))
        phone.network.metered = false
        phone.engine.syncNow()
        assertEquals(1, phone.remoteIndex()!!.objects.size)
    }

    @Test
    fun phoneSpaceCountsTheTwoGigabytesThatMustStayFree() {
        val gb = Limits.GIB
        assertEquals(PhoneSpace.OK, Limits.phoneSpace(4 * gb, 20 * gb, 8))
        assertEquals(PhoneSpace.NEARLY_FULL, Limits.phoneSpace(7 * gb, 20 * gb, 8))
        assertEquals(PhoneSpace.FULL, Limits.phoneSpace(15 * gb / 2, 20 * gb, 8))
        // Only 1 GB free on the phone: the effective limit shrinks so 2 GB stay free.
        assertEquals(PhoneSpace.FULL, Limits.phoneSpace(3 * gb, gb, 8))
    }
}
