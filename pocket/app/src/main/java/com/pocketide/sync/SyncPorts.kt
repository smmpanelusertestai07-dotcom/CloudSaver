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

    fun localSessions(): List<SessionRecord>

    fun localProjects(): List<Project>

    /** Sessions open in a room right now: their files are never cleaned. */
    fun activeSessionIds(): Set<String>

    fun roomsRunning(): Boolean

    suspend fun stopRooms()

    /** Moves a session to Recently deleted on this phone (the phone copy goes now). */
    suspend fun deleteSessionLocally(sessionId: String)

    /** Every Variable and Secret, serialized; null when the secrets store is not available. */
    suspend fun exportSecrets(): ByteArray?

    suspend fun importSecrets(bytes: ByteArray)

    fun phone(): PhoneSnapshot

    /** True while the computer is neither installing nor updating. */
    fun computerIdle(): Boolean

    suspend fun authorizeNewAccount(): DriveAuthResult

    /** New key and a new Half D in the account the app now uses (after a move). */
    suspend fun rekeyForMove()

    /** Removes every sealed entry of the secure store ("Delete everything"). */
    fun wipeSecureStore()
}
