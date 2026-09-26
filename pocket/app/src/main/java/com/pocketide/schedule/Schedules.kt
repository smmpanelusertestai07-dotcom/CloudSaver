package com.pocketide.schedule

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class ScheduledTask(
    val id: String,
    val projectId: String,
    val agentId: String,
    val title: String,
    val prompt: String,
    /** Hours between runs (24 = daily). */
    val everyHours: Int,
    val lastRunAt: Long? = null,
    val lastSessionId: String? = null,
    val enabled: Boolean = true,
    /**
     * The session of a run that has started and not yet ended. Still set when a run starts
     * again, the run before was cut off with no chance to say so.
     */
    val runningSessionId: String? = null,
    val runningSince: Long? = null,
)

/**
 * A saved prompt that runs through the agent's own command-line mode (`claude -p`, `codex exec`,
 * the agy CLI) only while the phone is charging on Wi-Fi. Each run becomes a session to review.
 */
interface Schedules {
    val tasks: StateFlow<List<ScheduledTask>>
    suspend fun save(task: ScheduledTask)
    suspend fun remove(id: String)
    suspend fun runNow(id: String): String?

    /**
     * "Delete everything" removed the tasks' file: no task runs again, and a task saved afterwards
     * starts a new list, so the old ones are never written back.
     */
    suspend fun forgetEverything() = Unit
}
