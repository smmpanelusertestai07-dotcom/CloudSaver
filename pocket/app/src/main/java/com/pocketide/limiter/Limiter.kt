package com.pocketide.limiter

import android.content.Context
import com.pocketide.model.Decision
import com.pocketide.model.Guard
import com.pocketide.model.PhoneSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Reads memory, heat, battery, storage, processes and network on a short interval. */
interface PhoneMonitor {
    val snapshot: StateFlow<PhoneSnapshot>
    fun start()
    fun stop()
    suspend fun refresh(): PhoneSnapshot
}

/** A condition Android or the phone maker imposes, with the owner's fix. */
data class Condition(val id: String, val title: String, val explanation: String, val fixLabel: String?, val fixIntentAction: String?)

/** Why a room stopped without the owner asking. */
enum class StopCause { IDLE, MEMORY, HEAT, BATTERY, ANDROID, REBOOT, UPDATE }

/** A stop worth a banner: what stopped, why, in one plain sentence, and that nothing was lost. */
data class RoomStop(val agentIds: List<String>, val cause: StopCause, val message: String, val at: Long)

/**
 * What a running room is doing. [busy] names what holds it (an agent turn, a command, a build
 * the agent waits on, a write); a busy room is never closed. [sleepsAt] is when idle sleep
 * closes it, null while busy or when idle sleep is off.
 */
data class RoomWork(val busy: Set<String>, val lastActiveAt: Long, val sleepsAt: Long?)

/** The app limits itself to what the phone can take (§11). */
interface Limiter {
    val guard: StateFlow<Guard>

    /** Current conditions worth a banner (restricted battery, OEM killer, Data Saver…). */
    val conditions: StateFlow<List<Condition>>

    fun canStartAgent(agentId: String): Decision

    fun canStartHeavyWork(what: String): Decision

    /** How many agents may run at once now (Auto from RAM, or the owner's setting). */
    fun maxAgents(): Int

    /** Meets the minimum requirements? Null when yes, else why not. */
    fun unsupportedReason(): String?

    /** Keyed by agent id, for every running room. */
    val work: StateFlow<Map<String, RoomWork>> get() = NO_WORK

    /** The latest stop the owner has not dismissed yet (banner with "Nothing was lost" and Resume). */
    val lastStop: StateFlow<RoomStop?> get() = NO_STOP

    fun dismissStop() = Unit

    /**
     * Something that must not be cut off started ([busy] true) or ended in [agentId]'s room:
     * [what] is e.g. "turn", "command", "build" or "write". Rooms, the terminal and the bridges call it.
     */
    fun setBusy(agentId: String, what: String, busy: Boolean) = Unit

    /** The room is in use (terminal output, Preview traffic to its ports, a message): idle sleep starts over. */
    fun touch(agentId: String) = Unit

    /**
     * Closes idle rooms that stand in the way of starting [agentId] (never a busy one), then
     * answers [canStartAgent] again. Rooms call it before opening a new room.
     */
    suspend fun makeRoomFor(agentId: String): Decision = canStartAgent(agentId)

    /**
     * Opens the fix for the condition [conditionId], trying the phone maker's own page first and
     * app info last. False when nothing could open.
     */
    fun openFix(context: Context, conditionId: String): Boolean = false

    /** The owner did the phone maker's one-time battery step. */
    fun markOemStepDone() = Unit
}

private val NO_WORK: StateFlow<Map<String, RoomWork>> = MutableStateFlow(emptyMap())
private val NO_STOP: StateFlow<RoomStop?> = MutableStateFlow(null)
