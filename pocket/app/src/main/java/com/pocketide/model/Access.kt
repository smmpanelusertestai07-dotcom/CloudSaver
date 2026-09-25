package com.pocketide.model

/** The state of a required connection (GitHub or Google Drive). */
enum class LinkHealth {
    NOT_CONNECTED,
    CHECKING,
    OK,
    /** No network. Offline is not disconnected: nothing locks. */
    OFFLINE,
    /** Access was revoked or the account is gone: the app locks until reconnected. */
    REVOKED,
}

/** Why the app is locked. Each has one clear screen with its fix. */
sealed interface LockReason {
    data object GitHubDisconnected : LockReason
    data object DriveDisconnected : LockReason
    /** PocketIDE's Drive share reached its limit, or the whole Google storage is full, for 24 h. */
    data class StorageFull(val googleStorageFull: Boolean) : LockReason
    /** Another phone took over the vault lease. */
    data class OtherPhone(val deviceName: String) : LockReason
    /** The phone does not meet the minimum (32-bit, no Play services, < Android 10, < 4 GB). */
    data class Unsupported(val why: String) : LockReason
}

/** What the lock screens and banners show. */
data class AccessState(
    val github: LinkHealth = LinkHealth.NOT_CONNECTED,
    val drive: LinkHealth = LinkHealth.NOT_CONNECTED,
    val lock: LockReason? = null,
    /** A non-blocking notice, e.g. "Your chats' key is only on this phone". */
    val banner: String? = null,
)
