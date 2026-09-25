package com.pocketide.sync

import com.pocketide.github.NotConnectedException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The vault's keyring is checked on every sync (§5.1), and a failed check never stops the sync. */
class KeyringCheckTest {
    private val clock = FakeClock()
    private val accounts = FakeAccounts(clock)
    private val path = claudeTranscript("owner/app", "s1", "c0ffee00-1111")

    private fun phone() = TestPhone(accounts, clock).apply {
        sessions += session("s1", at = clock.now, ref = "c0ffee00-1111")
        homeFile("claude", path).writeText("chat\n")
    }

    @Test
    fun everySyncChecksTheKeyring() = runBlocking {
        val phone = phone()
        phone.engine.syncNow()
        phone.engine.syncNow()
        assertEquals(2, phone.keyringChecks)
    }

    @Test
    fun offlineThereIsNothingToCheck() = runBlocking {
        val phone = phone()
        phone.network.online = false
        phone.engine.syncNow()
        assertEquals(0, phone.keyringChecks)
    }

    @Test
    fun withoutGitHubTheSyncStillFinishes() = runBlocking {
        val phone = phone()
        phone.keyringFailure = NotConnectedException("GitHub access was removed")
        phone.engine.syncNow()
        assertTrue(phone.engine.status.value is SyncStatus.UpToDate)
        assertNotNull(phone.remoteIndex())
    }

    @Test
    fun aFailedCheckIsTriedAgainAtTheNextSync() = runBlocking {
        val phone = phone()
        phone.keyringFailure = IOException("connection reset")
        phone.engine.syncNow()
        assertTrue(phone.engine.status.value is SyncStatus.UpToDate)
        phone.keyringFailure = null
        phone.engine.syncNow()
        assertEquals(2, phone.keyringChecks)
    }
}
