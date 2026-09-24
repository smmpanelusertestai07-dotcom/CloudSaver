package com.pocketide.limiter

import com.pocketide.model.Decision
import com.pocketide.model.Guard
import com.pocketide.model.PhoneSnapshot
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
}
