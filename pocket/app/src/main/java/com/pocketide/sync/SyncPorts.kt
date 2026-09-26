package com.pocketide.sync

import com.pocketide.core.AppDirs
import com.pocketide.core.Clock
import com.pocketide.core.SettingsStore
import com.pocketide.google.DriveAuthResult
import com.pocketide.google.DriveStore
import com.pocketide.model.PhoneSnapshot
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import com.pocketide.vault.VaultCipher

internal data class DeviceIdentity(val id: String, val name: String)

/** The network as it is at transfer time. */
internal interface NetworkProbe {
    fun online(): Boolean

    /** Mobile data or a metered Wi-Fi hotspot: counted against the daily limit. */
    fun metered(): Boolean

    /** Android's Data Saver is on and this app is not allowed background data. */
    fun dataSaverRestricted(): Boolean
}

/** A notification on the sync channel. [key] identifies the kind, so one kind replaces itself. */
internal data class Notice(val key: String, val title: String, val text: String)

internal interface SyncNotifier {
    fun post(notice: Notice)
}

/** WorkManager, seen from the engine. */
internal interface SyncScheduling {
    fun requestSoon()

    /** A sync as soon as a network is back, for what waits on the phone meanwhile. */
    fun requestWhenOnline()

    /** A sync after [delayMs], for a file left for later because it was still being written. */
    fun requestAfter(delayMs: Long)

    fun requestMaintenance()
    fun schedulePeriodic()
    fun cancelAll()
}

/**
 * Everything the sync engine needs from the rest of the app. Production wires it to the
 * [com.pocketide.AppGraph]; tests use fakes (an in-memory Drive, a fake cipher, temp folders).
 */
internal interface SyncPorts {
    val dirs: AppDirs
    val clock: Clock
    val device: DeviceIdentity
    val settings: SettingsStore
    val network: NetworkProbe
    val budget: MeteredDataBudget
    val notifier: SyncNotifier
    val scheduler: SyncScheduling

    /** The Drive store of the account the app is signed in to now. */
    fun drive(): DriveStore

    fun drive(email: String): DriveStore

    /** The signed-in Google account, when known. */
    fun account(): String?

    /** The vault cipher, or null while the key is not ready (set-up, restore, a lost key). */
    fun cipher(): VaultCipher?

    fun keyGeneration(): Int

    /**
     * Waits until the phone's own chat and project lists are read from the disk. Android often
     * starts the process just for a sync; until then [localSessions], [localProjects] and
     * [activeSessionIds] would say the phone has nothing.
     */
    suspend fun loadLocal() = Unit

    /**
     * "Delete everything" removed the phone's chat and project lists and its scheduled tasks from
     * the disk: they are forgotten in memory too, so nothing writes them back, the next vault never
     * receives the old records, and no old task runs again.
     */
    suspend fun forgetLocal()

    fun localSessions(): List<SessionRecord>

    fun localProjects(): List<Project>

    /** Sessions open in a room right now: their files are never cleaned. */
    fun activeSessionIds(): Set<String>

    fun roomsRunning(): Boolean

    fun roomRunning(agentId: String): Boolean

    suspend fun stopRooms()

    /** Moves a session to Recently deleted on this phone (the phone copy goes now). */
    suspend fun deleteSessionLocally(sessionId: String)

    /** Session records from the index: another phone's changes, a restore, conflict copies. */
    suspend fun adoptSessions(records: List<SessionRecord>)

    /** These sessions were erased from Drive; the phone forgets them too. */
    suspend fun sessionsErased(sessionIds: List<String>)

    /** Projects from the index (a restore, or another phone's new project). */
    suspend fun adoptProjects(projects: List<Project>)

    /**
     * A plain sentence when Android holds back this app's background work (a restricted standby
     * bucket, or background use turned off), so sync and the daily job run late; null otherwise.
     */
    fun backgroundLimit(): String?

    /** Every Variable and Secret, serialized; null when the secrets store is not available. */
    suspend fun exportSecrets(): ByteArray?

    /** Replaces this phone's Variables and Secrets with Drive's copy. */
    suspend fun importSecrets(bytes: ByteArray)

    /** Merges Drive's copy into this phone's Variables and Secrets (both changed since the last sync). */
    suspend fun mergeSecrets(bytes: ByteArray)

    fun phone(): PhoneSnapshot

    /** True while the computer is neither installing nor updating. */
    fun computerIdle(): Boolean

    /**
     * Removes the unused computer through its own module, which stops its programs, clears its
     * set-up record and shows it as not set up, so it is set up again on the next use.
     */
    suspend fun removeComputer()

    suspend fun authorizeNewAccount(): DriveAuthResult

    /** New key and a new Half D in the account the app now uses (after a move). */
    suspend fun rekeyForMove()

    /**
     * The vault's check of its GitHub keyring (private, no collaborators), which re-keys when the
     * key half there is exposed. Throws when GitHub is not connected or cannot be reached.
     */
    suspend fun checkKeyring()

    /**
     * Asks GitHub and Drive whether PocketIDE's access still stands, so a revoked account locks the
     * app before new work starts; the lock module keeps the answer. Offline locks nothing.
     */
    suspend fun checkAccess() = Unit

    /** Removes every sealed entry of the secure store ("Delete everything"). */
    fun wipeSecureStore()

    /** Drops the vault key from memory too, so the next set-up makes a new vault. */
    suspend fun forgetVaultKey()
}
