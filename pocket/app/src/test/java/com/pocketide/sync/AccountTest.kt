package com.pocketide.sync

import com.pocketide.core.Settings
import com.pocketide.core.ThemeMode
import com.pocketide.google.DriveAuthResult
import com.pocketide.model.Project
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
    fun piecesStillWaitingForTheirRecordAreSentToTheNewAccount() = runBlocking {
        val phone = syncedPhone()
        phone.homeFile("claude", path).appendText("more\n")
        // Same bytes as a screenshot Drive already has: queued as a reference to that file.
        phone.mediaFile("claude", "owner/app", "s1", "copy.png").writeBytes(ByteArray(40) { 9 })
        clock.advance(Durations.MINUTE)
        // The new piece reaches the old account, but its record there fails.
        phone.drive.failIndexWrites = 1
        phone.engine.syncNow()
        assertEquals(1, phone.queued().count { it.blob && it.driveId != null })
        assertEquals(1, phone.queued().count { !it.blob })

        phone.newAccount = DriveAuthResult.Authorized(newEmail)
        phone.onAuthorize = { phone.account = newEmail }
        phone.engine.moveToAnotherAccount()
        phone.engine.eraseOldAccountCopy()
        clock.advance(Durations.MINUTE)
        phone.engine.syncNow()

        val target = accounts[newEmail]
        val index = RemoteIndex().decode(phone.cipher, target.named(RemoteIndex.NAME).single().bytes)
        assertEquals(listOf(0L, 5L), index.objects.filter { it.path == path }.map { it.offset }.sorted())
        assertTrue("every entry names its own file in the new account", index.objects.all { o -> target.files[o.driveId]?.name == o.name })
        val reader = TestPhone(accounts, clock, deviceId = "reader", deviceName = "Reader", account = newEmail)
        reader.engine.fetchSession("s1")
        assertEquals("chat\nmore\n", reader.homeFile("claude", path).readText())
        assertEquals(40, reader.mediaFile("claude", "owner/app", "s1", "copy.png").length().toInt())
    }

    @Test
    fun signingInToAnotherAccountSendsThePhonesFilesWholeToItsVault() = runBlocking {
        val phone = syncedPhone()
        phone.homeFile("claude", path).appendText("more\n")
        clock.advance(Durations.MINUTE)
        // The new piece reaches the old account, but its record there fails.
        phone.drive.failIndexWrites = 1
        phone.engine.syncNow()
        assertEquals(1, phone.queued().count { it.blob && it.driveId != null })

        // The owner signs in to another account, not through a move: a vault of its own.
        phone.account = newEmail
        clock.advance(Durations.MINUTE)
        phone.engine.syncNow()

        val target = accounts[newEmail]
        val index = RemoteIndex().decode(phone.cipher, target.named(RemoteIndex.NAME).single().bytes)
        assertTrue("every entry names its own file in the new account", index.objects.all { o -> target.files[o.driveId]?.name == o.name })
        assertEquals(listOf(0L), index.objects.filter { it.path == path }.map { it.offset })
        val reader = TestPhone(accounts, clock, deviceId = "reader", deviceName = "Reader", account = newEmail)
        reader.engine.fetchSession("s1")
        assertEquals("chat\nmore\n", reader.homeFile("claude", path).readText())
        assertEquals(40, reader.mediaFile("claude", "owner/app", "s1", "shot.png").length().toInt())
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
    fun aMoveWaitingForItsNewAccountKeepsSyncingTheOldOne() = runBlocking {
        val phone = syncedPhone()
        phone.newAccount = DriveAuthResult.NeedsConsent(mockPendingIntent())
        phone.engine.moveToAnotherAccount()
        assertTrue(phone.engine.move.value is MoveState.NeedsConsent)

        // Google's sheet was approved: the new account is the one in use before the move is told which it is.
        phone.account = newEmail
        phone.homeFile("claude", path).appendText("more\n")
        clock.advance(Durations.MINUTE)
        phone.engine.syncNow()
        assertTrue("the move survives a background sync", phone.state().move != null)
        assertEquals(TestPhone.OWNER, phone.state().account)
        assertTrue(accounts[newEmail].objectNames().isEmpty())

        phone.engine.moveToAccount(newEmail)
        assertEquals(MoveState.ReadyToEraseOld(TestPhone.OWNER, newEmail), phone.engine.move.value)
        assertEquals(accounts[TestPhone.OWNER].objectNames(), accounts[newEmail].objectNames())
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
        assertTrue("the old key goes from memory too, so set-up makes a new vault", phone.vaultKeyForgotten)
        assertFalse(phone.settings.settings.value.onboardingDone)
        assertEquals(1, phone.scheduler.cancelled)
        assertEquals(SyncStatus.Idle, phone.engine.status.value)
        assertTrue(phone.engine.driveSessions.value.isEmpty())
    }

    @Test
    fun noChatOrProjectReachesTheNextVaultAfterDeleteEverything() = runBlocking {
        val phone = syncedPhone()
        phone.projects += Project("owner/app", "owner", "app", addedAt = clock.now, lastActivityAt = clock.now)
        phone.engine.syncNow()
        assertEquals(listOf("owner/app"), phone.remoteIndex()!!.projects.map { it.id })

        phone.engine.deleteEverything()
        phone.settings.update { it.copy(onboardingDone = true) }
        phone.engine.syncNow()

        val index = phone.remoteIndex()!!
        assertTrue(index.sessions.isEmpty())
        assertTrue(index.projects.isEmpty())
    }

    @Test
    fun noOldSettingReachesTheNextVaultAfterDeleteEverything() = runBlocking {
        val phone = syncedPhone()
        phone.settings.update {
            it.copy(keepChatsMonths = 12, driveLimitGb = 5, onlyOfficialAgents = true, extraPassword = true, privacyChecklistDone = true)
        }
        phone.engine.syncNow()
        assertEquals(12, SyncedSettings.parse(phone.remoteIndex()!!.settingsJson!!)!!.keepChatsMonths)

        phone.engine.deleteEverything()
        // Erased as the owner was told: set-up starts over, with no extra password on a key that is gone.
        assertEquals(Settings(), phone.settings.settings.value)
        phone.settings.update { it.copy(onboardingDone = true) }
        phone.engine.syncNow()

        assertEquals(SyncedSettings.of(Settings()), SyncedSettings.parse(phone.remoteIndex()!!.settingsJson!!))
    }

    @Test
    fun theAppsOwnConfigurationOnThisPhoneStaysAfterDeleteEverything() = runBlocking {
        val phone = syncedPhone()
        phone.settings.update {
            it.copy(
                gitHubAppClientId = "owners-client-id",
                gitHubAppSlug = "owners-pocketide",
                oemStepDone = true,
                theme = ThemeMode.DARK,
                claudeChatsInAccount = false,
            )
        }

        phone.engine.deleteEverything()

        // Without its GitHub App, a copy built without one could not even sign in again; the battery
        // step lives in Android's settings, which Delete everything does not touch. The rest resets.
        val kept = Settings(gitHubAppClientId = "owners-client-id", gitHubAppSlug = "owners-pocketide", oemStepDone = true)
        assertEquals(kept, phone.settings.settings.value)
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
