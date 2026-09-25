package com.pocketide.lock

import com.pocketide.core.Clock
import com.pocketide.model.LinkHealth
import com.pocketide.model.LockReason
import com.pocketide.sync.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccessGuardTest {

    private class FakeSources : AccessSources {
        var github: suspend () -> LinkHealth = { LinkHealth.OK }
        var drive: suspend () -> LinkHealth = { LinkHealth.OK }
        val lease = MutableStateFlow<String?>(null)
        val sync = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
        val key = MutableStateFlow(false)
        var unsupported: String? = null
        override fun unsupported() = unsupported
        override suspend fun githubHealth() = github()
        override suspend fun driveHealth() = drive()
        override fun leaseHolder(): Flow<String?> = lease
        override fun syncStatus(): Flow<SyncStatus> = sync
        override fun keyOnlyOnPhone(): Flow<Boolean> = key
    }

    private fun guard(scope: TestScope, sources: FakeSources, now: () -> Long = { 0 }) =
        AccessGuardImpl(sources, Clock { now() }, scope.backgroundScope, schedule = {}).also { it.start() }

    @Test
    fun `revoked GitHub locks, and going offline afterwards does not unlock it`() = runTest {
        val sources = FakeSources()
        val guard = guard(this, sources)
        sources.github = { LinkHealth.REVOKED }
        guard.check()
        assertEquals(LockReason.GitHubDisconnected, guard.state.value.lock)

        sources.github = { LinkHealth.OFFLINE }
        guard.check()
        assertEquals(LinkHealth.REVOKED, guard.state.value.github)
        assertEquals(LockReason.GitHubDisconnected, guard.state.value.lock)

        sources.github = { LinkHealth.OK }
        guard.check()
        assertNull(guard.state.value.lock)
    }

    @Test
    fun `offline is shown as offline and locks nothing`() = runTest {
        val sources = FakeSources().apply {
            github = { LinkHealth.OFFLINE }
            drive = { throw java.io.IOException("no route") }
        }
        val guard = guard(this, sources)
        guard.check()
        assertEquals(LinkHealth.OFFLINE, guard.state.value.github)
        assertEquals(LinkHealth.OFFLINE, guard.state.value.drive)
        assertNull(guard.state.value.lock)
    }

    @Test
    fun `a link that never answers counts as offline`() = runTest {
        val sources = FakeSources().apply { drive = { awaitCancellation() } }
        val guard = guard(this, sources)
        guard.check()
        assertEquals(LinkHealth.OFFLINE, guard.state.value.drive)
        assertNull(guard.state.value.lock)
    }

    @Test
    fun `another phone taking the lease locks at once, and releasing it unlocks`() = runTest {
        val sources = FakeSources()
        val guard = guard(this, sources)
        runCurrent()
        sources.lease.value = "Pixel 8"
        runCurrent()
        assertEquals(LockReason.OtherPhone("Pixel 8"), guard.state.value.lock)
        sources.lease.value = null
        runCurrent()
        assertNull(guard.state.value.lock)
    }

    @Test
    fun `the storage wait locks after a day even with no new sync pass`() = runTest {
        var now = 0L
        val sources = FakeSources()
        val guard = guard(this, sources) { now }
        sources.sync.value = SyncStatus.Waiting("PocketIDE's space is full", since = 0, pendingBytes = 1024)
        runCurrent()
        assertNull(guard.state.value.lock)
        now = 24 * 60 * 60_000L
        guard.check()
        assertEquals(LockReason.StorageFull(false), guard.state.value.lock)
    }

    @Test
    fun `unsupported wins, and the key banner follows the vault`() = runTest {
        val sources = FakeSources().apply { unsupported = "This phone has a 32-bit processor." }
        val guard = guard(this, sources)
        sources.key.value = true
        runCurrent()
        assertEquals(LockReason.Unsupported("This phone has a 32-bit processor."), guard.state.value.lock)
        assertEquals(AccessRules.KEY_BANNER, guard.state.value.banner)
    }
}
