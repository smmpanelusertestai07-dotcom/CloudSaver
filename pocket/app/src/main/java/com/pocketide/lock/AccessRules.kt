package com.pocketide.lock

import com.pocketide.model.LinkHealth
import com.pocketide.model.LockReason
import com.pocketide.sync.SyncStatus

/** Everything the lock decision reads, at one moment. */
internal data class AccessFacts(
    val unsupported: String?,
    val github: LinkHealth,
    val drive: LinkHealth,
    /** The other phone's name when it holds the vault lease. */
    val leaseHolder: String?,
    val sync: SyncStatus,
    val keyOnlyOnPhone: Boolean,
)

/**
 * Which lock screen wins, most fundamental first: a phone that cannot run PocketIDE, then
 * GitHub, then Drive, then another phone, then storage. Only revoked access locks: offline,
 * checking and not-yet-connected never do (onboarding handles the last).
 */
internal object AccessRules {
    const val STORAGE_LOCK_AFTER_MS = 24 * 60 * 60_000L
    const val STORAGE_LOCK_BYTES = 200L * 1024 * 1024

    const val KEY_BANNER = "Your chats' key is only on this phone. Reconnect GitHub to protect it."

    fun lock(facts: AccessFacts, now: Long): LockReason? = when {
        facts.unsupported != null -> LockReason.Unsupported(facts.unsupported)
        facts.github == LinkHealth.REVOKED -> LockReason.GitHubDisconnected
        facts.drive == LinkHealth.REVOKED -> LockReason.DriveDisconnected
        facts.leaseHolder != null -> LockReason.OtherPhone(facts.leaseHolder)
        storageLocks(facts.sync, now) -> LockReason.StorageFull((facts.sync as SyncStatus.Waiting).googleStorageFull)
        else -> null
    }

    fun banner(facts: AccessFacts): String? = if (facts.keyOnlyOnPhone) KEY_BANNER else null

    /**
     * New chats have waited for Drive space for a day, or 200 MB of them wait: the app locks
     * until space is made, so nothing more piles up that could be lost with the phone. Counted
     * here with this phone's clock too, so a day passes even when no sync runs to notice it.
     */
    fun storageLocks(sync: SyncStatus, now: Long): Boolean = sync is SyncStatus.Waiting &&
        (sync.locks || now - sync.since >= STORAGE_LOCK_AFTER_MS || sync.pendingBytes >= STORAGE_LOCK_BYTES)
}
