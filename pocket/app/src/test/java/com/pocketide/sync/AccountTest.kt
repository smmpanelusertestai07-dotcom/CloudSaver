package com.pocketide.sync

import com.pocketide.google.DriveAuthResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountTest {
    private val clock = FakeClock()
    private val accounts = FakeAccounts(clock)
    private val path = claudeTranscript("owner/app", "s1", "c0ffee00-1111")
    private val newEmail = "new@example.com"

    private suspend fun syncedPhone(): TestPhone {
        val phone = TestPhone(accounts, clock).apply { sessions += session("s1", at = clock.now, ref = "c0ffee00-1111") }
        phone.homeFile("claude", path).writeText("chat\n")
        phone.homeFile("claude", ".claude/CLAUDE.md").writeText("rules")
        phone.mediaFile("claude", "owner/app", "s1", "shot.png").writeBytes(ByteArray(40) { 9 })
        phone.engine.syncNow()
        // The vault's own files live beside the sync engine's.
        phone.drive.uploadBytes("keyhalf-d", byteArrayOf(1))
        phone.drive.uploadBytes("keycheck", byteArrayOf(2))
        return phone
    }

    @Test
    fun movingCopiesEveryFileOneAtATimeAndErasesTheOldCopyOnlyWhenAsked() = runBlocking {
        val phone = syncedPhone()
        val oldDrive = phone.drive
        val oldObjects = oldDrive.objectNames()
        // Google's account picker makes the new account the one the app (and the vault) uses.
        phone.newAccount = DriveAuthResult.Authorized(newEmail)
        phone.onAuthorize = { phone.account = newEmail }

        phone.engine.moveToAnotherAccount()

        val target = accounts[newEmail]
        assertEquals(oldObjects, target.objectNames())
        assertEquals(1, phone.rekeys)
        assertEquals(1, target.named("keyhalf-d").size)
        assertEquals(MoveState.ReadyToEraseOld(TestPhone.OWNER, newEmail), phone.engine.move.value)
        assertTrue("the old copy stays until the owner agrees", oldObjects.all { it in oldDrive.objectNames() })
        assertTrue(phone.state().move != null)

        phone.engine.eraseOldAccountCopy()
        assertTrue(oldDrive.files.isEmpty())
        assertEquals(MoveState.Done(newEmail), phone.engine.move.value)

        // Syncing continues in the new account.
        phone.homeFile("claude", path).appendText("more\n")
        clock.advance(Durations.MINUTE)
        phone.engine.syncNow()
        assertTrue(oldDrive.files.isEmpty())
        val index = RemoteIndex().decode(phone.cipher, target.named(RemoteIndex.NAME).single().bytes)
        assertEquals(listOf(0L, 5L), index.objects.filter { it.path == path }.map { it.offset }.sorted())
        assertTrue(index.objects.all { o -> target.files[o.driveId]?.name == o.name })
    }

    @Test
    fun aMoveCutOffHalfwayContinuesWithoutCopyingTwice() = runBlocking {
        val phone = syncedPhone()
        phone.onAuthorize = { phone.account = newEmail }
        phone.newAccount = DriveAuthResult.Authorized(newEmail)
        val target = accounts[newEmail]
        var copies = 0
        target.beforeUpload = { name ->
            if (name.startsWith("o-")) {
                copies++
                if (copies == 2) throw com.pocketide.google.DriveException.Offline()
            }
        }
        runCatching { phone.engine.moveToAnotherAccount() }
        assertTrue(phone.engine.move.value is MoveState.Failed)
        target.beforeUpload = null

        phone.engine.moveToAccount(newEmail)
        assertEquals(accounts[TestPhone.OWNER].objectNames(), target.objectNames())
        assertEquals(target.objectNames().size, target.files.values.count { it.name.startsWith("o-") })
        assertTrue(phone.engine.move.value is MoveState.ReadyToEraseOld)
    }

    @Test
    fun deleteEverythingErasesDriveAndThePhone() = runBlocking {
        val phone = syncedPhone()
        phone.homeFile("claude", ".codex/auth.json")
        phone.dirs.repos.mkdirs()
        phone.dirs.builds.mkdirs()
        phone.engine.deleteEverything()

        assertTrue(phone.drive.files.isEmpty())
        for (dir in listOf(phone.dirs.rooms, phone.dirs.work, phone.dirs.repos, phone.dirs.vault, phone.dirs.queue, phone.dirs.builds)) {
            assertFalse(dir.path, dir.exists())
        }
        assertTrue(phone.secureStoreWiped)
        assertFalse(phone.settings.settings.value.onboardingDone)
        assertEquals(1, phone.scheduler.cancelled)
        assertEquals(SyncStatus.Idle, phone.engine.status.value)
        assertTrue(phone.engine.driveSessions.value.isEmpty())
    }

    @Test
    fun deleteEverythingNeedsTheInternetAndLeavesThePhoneAsItWas() = runBlocking {
        val phone = syncedPhone()
        phone.network.online = false
        val failure = runCatching { phone.engine.deleteEverything() }.exceptionOrNull()
        assertTrue(failure is SyncException)
        assertTrue(phone.dirs.rooms.exists())
        assertTrue(phone.drive.files.isNotEmpty())
    }
}
