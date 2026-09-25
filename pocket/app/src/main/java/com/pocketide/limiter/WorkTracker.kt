package com.pocketide.limiter

/**
 * "Idle" defined by work, not by the screen: a room is busy while an agent is mid-turn, a
 * command or a build it waits on runs, or a write is in progress, and in use whenever terminal
 * output or Preview traffic touches it. Only a room that is neither may be put to sleep.
 * Not thread-safe: the limiter calls it under its own lock.
 */
internal class WorkTracker(private val now: () -> Long) {
    private val holds = HashMap<String, MutableSet<String>>()
    private val lastActive = HashMap<String, Long>()

    fun setBusy(agentId: String, what: String, busy: Boolean) {
        val set = holds.getOrPut(agentId) { mutableSetOf() }
        if (busy) set += what else set -= what
        lastActive[agentId] = now()
    }

    fun touch(agentId: String) {
        lastActive[agentId] = now()
    }

    fun isBusy(agentId: String): Boolean = holds[agentId].orEmpty().isNotEmpty()

    /** Starts the idle clock for rooms that just came up and forgets rooms that stopped. */
    fun follow(running: Set<String>) {
        for (id in running) lastActive.putIfAbsent(id, now())
        lastActive.keys.retainAll(running)
        holds.keys.retainAll(running)
    }

    /** [idleSleepMs] of 0 or less means idle sleep is off. */
    fun work(running: Set<String>, idleSleepMs: Long): Map<String, RoomWork> = running.associateWith { id ->
        val busy = holds[id].orEmpty().toSet()
        val last = lastActive[id] ?: now()
        RoomWork(busy = busy, lastActiveAt = last, sleepsAt = if (busy.isEmpty() && idleSleepMs > 0) last + idleSleepMs else null)
    }

    /** Rooms whose idle time ran out. */
    fun sleepy(running: Set<String>, idleSleepMs: Long): List<String> =
        work(running, idleSleepMs).filterValues { it.sleepsAt != null && it.sleepsAt <= now() }.keys.toList()

    /** Idle rooms, the longest idle first, that have been idle at least [minIdleMs]. */
    fun idleLongest(running: Set<String>, minIdleMs: Long): List<String> = running
        .filter { !isBusy(it) && now() - (lastActive[it] ?: now()) >= minIdleMs }
        .sortedBy { lastActive[it] ?: now() }
}
