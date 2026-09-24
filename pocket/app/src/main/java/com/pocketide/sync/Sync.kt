package com.pocketide.sync

import kotlinx.coroutines.flow.StateFlow

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Running(val what: String) : SyncStatus
    data class UpToDate(val at: Long) : SyncStatus
    /** New chats wait safely on the phone; [since] drives the 24-hour lock. */
    data class Waiting(val why: String, val since: Long, val pendingBytes: Long) : SyncStatus
    data class Error(val why: String) : SyncStatus
}

data class PendingUpload(val sessionId: String, val title: String, val bytes: Long, val videos: Int)

/** What a new phone downloads now and what waits in Drive (restore plan, §6.9). */
data class RestorePlan(
    val driveTotalBytes: Long,
    val downloadNowBytes: Long,
    val laterBytes: Long,
    val sessionsNow: Int,
    val sessionsLater: Int,
    val onWifi: Boolean,
    val freeBytes: Long,
)

enum class RestoreChoice { WIFI_ONLY, MOBILE_UP_TO_LIMIT }

/** Monthly data usage by type, metered networks only (Wi-Fi is free). */
data class DataUsage(val todayMeteredBytes: Long, val monthMeteredBytes: Long, val byType: Map<String, Long>)

/**
 * Durable, append-only sync of AI data to the Drive hidden folder. New transcript bytes become
 * small compressed, encrypted pieces; images go with the chat; videos wait for Wi-Fi unless the
 * owner allows mobile data; nothing is marked synced until Drive confirms it. Holds the lease
 * (one active phone) and keeps conflict copies.
 */
interface SyncEngine {
    val status: StateFlow<SyncStatus>
    val waiting: StateFlow<List<PendingUpload>>
    val usage: StateFlow<DataUsage>

    /** Schedules a sync soon (end of a task, or every few minutes while agents run). */
    fun requestSync(reason: String)

    suspend fun syncNow()

    suspend fun uploadNow(sessionIds: List<String>)

    suspend fun restorePlan(): RestorePlan

    suspend fun restore(choice: RestoreChoice)

    /** Downloads an older session when the owner opens it. */
    suspend fun fetchSession(sessionId: String)

    /** "Use here?" on a second phone: takes the lease; the first phone locks at its next check. */
    suspend fun takeOver()

    /** Moves every vault file to another Google account, one file at a time. */
    suspend fun moveToAnotherAccount()

    /** Erases everything in Drive and on the phone ("Delete everything"). */
    suspend fun deleteEverything()

    /** Starts the periodic sync and the daily maintenance job. */
    fun schedule()
}

/** Metered-only accounting and the daily limit, checked before every big transfer. */
interface DataBudget {
    /** May [bytes] of kind [kind] be transferred now? Big items wait for Wi-Fi by default. */
    fun allow(bytes: Long, kind: String, big: Boolean): com.pocketide.model.Decision

    fun record(bytes: Long, kind: String)
}
