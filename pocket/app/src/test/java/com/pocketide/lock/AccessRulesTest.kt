package com.pocketide.lock

import com.pocketide.model.LinkHealth
import com.pocketide.model.LockReason
import com.pocketide.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessRulesTest {
    private val hour = 60 * 60_000L
    private val day = 24 * hour
    private val mb = 1024L * 1024

    private fun facts(
        unsupported: String? = null,
        github: LinkHealth = LinkHealth.OK,
        drive: LinkHealth = LinkHealth.OK,
        lease: String? = null,
        sync: SyncStatus = SyncStatus.UpToDate(0),
        keyOnlyOnPhone: Boolean = false,
    ) = AccessFacts(unsupported, github, drive, lease, sync, keyOnlyOnPhone)

    private val storageFull = SyncStatus.Waiting("Drive is full", since = 0, pendingBytes = 5 * mb, googleStorageFull = true, locks = true)

    @Test
    fun `nothing wrong, nothing locked`() {
        assertNull(AccessRules.lock(facts(), 0))
    }

    @Test
    fun `precedence is unsupported, GitHub, Drive, other phone, storage`() {
        val everything = facts("32-bit", LinkHealth.REVOKED, LinkHealth.REVOKED, "Pixel 8", storageFull)
        assertEquals(LockReason.Unsupported("32-bit"), AccessRules.lock(everything, 0))
        assertEquals(LockReason.GitHubDisconnected, AccessRules.lock(everything.copy(unsupported = null), 0))
        assertEquals(LockReason.DriveDisconnected, AccessRules.lock(everything.copy(unsupported = null, github = LinkHealth.OK), 0))
        assertEquals(
            LockReason.OtherPhone("Pixel 8"),
            AccessRules.lock(everything.copy(unsupported = null, github = LinkHealth.OK, drive = LinkHealth.OK), 0),
        )
        assertEquals(
            LockReason.StorageFull(googleStorageFull = true),
            AccessRules.lock(everything.copy(unsupported = null, github = LinkHealth.OK, drive = LinkHealth.OK, leaseHolder = null), 0),
        )
    }

    @Test
    fun `offline is not revoked, and never locks`() {
        for (health in listOf(LinkHealth.OFFLINE, LinkHealth.CHECKING, LinkHealth.NOT_CONNECTED)) {
            assertNull("github $health", AccessRules.lock(facts(github = health), 0))
            assertNull("drive $health", AccessRules.lock(facts(drive = health), 0))
        }
        assertEquals(LockReason.GitHubDisconnected, AccessRules.lock(facts(github = LinkHealth.REVOKED, drive = LinkHealth.OFFLINE), 0))
    }

    @Test
    fun `storage locks after 24 hours of waiting, by this phone's clock`() {
        val since = 1_000_000L
        val waiting = SyncStatus.Waiting("PocketIDE's space is full", since = since, pendingBytes = 10 * mb)
        assertNull(AccessRules.lock(facts(sync = waiting), since + day - 1))
        assertEquals(LockReason.StorageFull(googleStorageFull = false), AccessRules.lock(facts(sync = waiting), since + day))
    }

    @Test
    fun `storage locks at once when 200 MB wait, or when sync says so`() {
        val waiting = SyncStatus.Waiting("PocketIDE's space is full", since = 0, pendingBytes = 199 * mb)
        assertNull(AccessRules.lock(facts(sync = waiting), hour))
        assertEquals(LockReason.StorageFull(false), AccessRules.lock(facts(sync = waiting.copy(pendingBytes = 200 * mb)), hour))
        assertEquals(LockReason.StorageFull(false), AccessRules.lock(facts(sync = waiting.copy(locks = true)), hour))
    }

    @Test
    fun `other sync states never lock`() {
        for (status in listOf(SyncStatus.Idle, SyncStatus.Running("pieces"), SyncStatus.Error("offline"), SyncStatus.UpToDate(0))) {
            assertNull("$status", AccessRules.lock(facts(sync = status), 10 * day))
        }
    }

    @Test
    fun `the key banner shows while the key is only on this phone, and asks to reconnect only without GitHub`() {
        assertEquals(AccessRules.KEY_BANNER, AccessRules.banner(facts(github = LinkHealth.NOT_CONNECTED, keyOnlyOnPhone = true)))
        // GitHub is connected: the halves are only waiting for the next sync.
        assertEquals(AccessRules.KEY_SAVING_BANNER, AccessRules.banner(facts(keyOnlyOnPhone = true)))
        assertEquals(AccessRules.KEY_SAVING_BANNER, AccessRules.banner(facts(github = LinkHealth.OFFLINE, keyOnlyOnPhone = true)))
        assertNull(AccessRules.banner(facts()))
    }
}
