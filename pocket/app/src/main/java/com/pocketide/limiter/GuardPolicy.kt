package com.pocketide.limiter

import com.pocketide.model.Decision
import com.pocketide.model.Guard
import com.pocketide.model.PhoneSnapshot
import com.pocketide.model.Thermal
import java.util.Locale

/**
 * What one more room costs. Memory is what a room holds once its engine is up; processes are
 * what it adds to Android's system-wide cap of 32 phantom processes (Android 12+). Both are
 * budgets to measure against in Phase 0 on the owner's 4 GB phone, not guarantees.
 */
enum class RoomKind(val memoryBytes: Long, val processes: Int) {
    /** code-server, its Node host, the extension host and the agent's own binary. */
    CODE_SERVER(800L * MB, 8),

    /** Antigravity's `agy` hub on its own. */
    HUB(200L * MB, 4),
}

/** The guard with the sentence that explains it; [why] is null only for [Guard.OK]. */
data class GuardVerdict(val guard: Guard, val why: String?)

/** Every threshold of §11 in one place, as plain functions of a snapshot, so they can be tested. */
object GuardPolicy {
    /** Android 12+ kills phantom processes beyond this many, counted across all apps. */
    const val PHANTOM_CAP = 32

    /** Where PocketIDE stops adding rooms, leaving a little of the cap for everyone else. */
    const val PROCESS_BUDGET = 28

    /** Below this much RAM, a phone is a "4 GB phone": one code-server room at a time, two agents. */
    const val LARGE_PHONE_RAM = 5_000_000_000L

    /**
     * The guard for this moment. Charging lifts every battery rule (charging resumes); heat is
     * never lifted by charging. [nextRoom] is the cheapest room not yet running, or null when no
     * further room may start.
     */
    fun evaluate(snapshot: PhoneSnapshot, nextRoom: RoomKind?): GuardVerdict {
        if (snapshot.at == 0L) return GuardVerdict(Guard.OK, null)
        val battery = snapshot.batteryPercent.takeUnless { snapshot.charging }
        val thermal = snapshot.thermal
        return when {
            thermal >= Thermal.CRITICAL -> GuardVerdict(Guard.SAFE_STOP, "The phone is too hot. Agents stopped safely until it cools down.")
            battery != null && battery <= 5 -> GuardVerdict(Guard.SAFE_STOP, "The battery is at $battery %. Agents stopped safely until the phone charges.")
            thermal >= Thermal.SEVERE -> GuardVerdict(Guard.PAUSE, "The phone is hot. Agents pause after their current step until it cools down.")
            battery != null && battery <= 10 -> GuardVerdict(Guard.PAUSE, "The battery is at $battery %. Agents pause after their current step until the phone charges.")
            snapshot.processCount >= PROCESS_BUDGET -> GuardVerdict(
                Guard.NO_NEW_AGENTS,
                "PocketIDE runs ${snapshot.processCount} processes, close to Android's limit of $PHANTOM_CAP. Stop an agent before starting another.",
            )
            nextRoom != null && snapshot.availRamBytes < nextRoom.memoryBytes -> GuardVerdict(
                Guard.NO_NEW_AGENTS,
                "Only ${bytes(snapshot.availRamBytes)} of memory is free. Stop an idle agent or close other apps first.",
            )
            thermal >= Thermal.MODERATE -> GuardVerdict(Guard.NO_NEW_HEAVY, "The phone is warm. New agents and heavy work wait until it cools down.")
            battery != null && battery <= 20 -> GuardVerdict(Guard.NO_NEW_HEAVY, "The battery is at $battery %. New agents and heavy work wait until the phone charges.")
            else -> GuardVerdict(Guard.OK, null)
        }
    }

    /**
     * The cheapest kind of room that is not running yet: what "one more room" needs. Null when
     * [maxAgents] rooms already run, since no further room may start whatever memory is free.
     */
    fun nextRoom(running: Collection<RoomKind>, maxAgents: Int): RoomKind? = when {
        running.size >= maxAgents -> null
        RoomKind.HUB !in running -> RoomKind.HUB
        RoomKind.CODE_SERVER !in running -> RoomKind.CODE_SERVER
        // Another code-server room (a discovered agent) costs the same again.
        else -> RoomKind.CODE_SERVER
    }

    /** Auto: a 4 GB phone holds one of Claude or Codex plus Antigravity; 6 GB and more hold all three. */
    fun maxAgents(totalRamBytes: Long, ownerChoice: Int): Int = when {
        ownerChoice > 0 -> ownerChoice
        totalRamBytes in 1 until LARGE_PHONE_RAM -> 2
        else -> 3
    }

    /**
     * May [agentId] start now? [running] holds every room that is starting or running, with its
     * kind; [names] turns an agent id into the name the owner knows.
     */
    fun canStart(
        agentId: String,
        kind: RoomKind,
        running: Map<String, RoomKind>,
        verdict: GuardVerdict,
        snapshot: PhoneSnapshot,
        maxAgents: Int,
        names: (String) -> String,
    ): Decision {
        if (agentId in running) return Decision.YES
        if (verdict.guard != Guard.OK) return Decision.no(verdict.why ?: "The phone needs a rest first.")
        if (running.size >= maxAgents) {
            val list = running.keys.joinToString(" and ") { names(it) }
            val count = if (maxAgents == 1) "One agent runs" else "$maxAgents agents run"
            return Decision.no("$count at a time on this phone, and $list ${if (running.size == 1) "is" else "are"} running. Stop one first.")
        }
        val smallPhone = snapshot.totalRamBytes in 1 until LARGE_PHONE_RAM
        val otherEngine = running.entries.firstOrNull { it.value == RoomKind.CODE_SERVER }
        if (kind == RoomKind.CODE_SERVER && smallPhone && otherEngine != null) {
            return Decision.no("On a phone with 4 GB of memory, one of Claude or Codex runs at a time. Stop ${names(otherEngine.key)} first.")
        }
        if (snapshot.at > 0 && snapshot.availRamBytes < kind.memoryBytes) {
            return Decision.no(
                "${names(agentId)} needs about ${bytes(kind.memoryBytes)} of free memory and ${bytes(snapshot.availRamBytes)} is free. " +
                    "Stop an idle agent or close other apps first.",
            )
        }
        if (snapshot.processCount + kind.processes > PHANTOM_CAP) {
            return Decision.no("Another agent would pass Android's limit of $PHANTOM_CAP processes. Stop an agent first.")
        }
        return Decision.YES
    }

    /**
     * The idle rooms to close so [agentId] may start, taken from [idleFirst] (longest idle first),
     * or null when closing idle rooms would not be enough (low battery, heat, busy rooms). Their
     * memory and processes are counted as freed: the processes each room was [measured] to run
     * (Rooms.processes), or its kind's budget when it was not measured yet.
     */
    fun roomsToClose(
        agentId: String,
        kind: RoomKind,
        running: Map<String, RoomKind>,
        idleFirst: List<String>,
        snapshot: PhoneSnapshot,
        maxAgents: Int,
        measured: Map<String, Int> = emptyMap(),
    ): List<String>? {
        val remaining = running.toMutableMap()
        var after = snapshot
        val closing = mutableListOf<String>()
        fun allowed(): Boolean {
            val verdict = evaluate(after, nextRoom(remaining.values, maxAgents))
            return canStart(agentId, kind, remaining, verdict, after, maxAgents) { it }.allowed
        }
        if (allowed()) return emptyList()
        for (id in idleFirst) {
            val freed = remaining.remove(id) ?: continue
            closing += id
            after = after.copy(
                availRamBytes = after.availRamBytes + freed.memoryBytes,
                processCount = (after.processCount - (measured[id] ?: freed.processes)).coerceAtLeast(0),
            )
            if (allowed()) return closing
        }
        return null
    }

    /** Builds, installs and updates: only on an OK guard, and not while Android reports low memory. */
    fun canStartHeavy(what: String, verdict: GuardVerdict, snapshot: PhoneSnapshot): Decision = when {
        verdict.guard != Guard.OK -> Decision.no("$what waits. ${verdict.why.orEmpty()}".trim())
        snapshot.lowMemory -> Decision.no("$what waits. The phone is low on memory right now.")
        else -> Decision.YES
    }
}

internal const val MB = 1024L * 1024L
private const val GB = 1024L * MB

/** Sizes as the owner reads them: "780 MB", "3.4 GB". */
internal fun bytes(value: Long): String {
    val v = value.coerceAtLeast(0)
    return if (v >= GB) String.format(Locale.ENGLISH, "%.1f GB", v.toDouble() / GB) else "${v / MB} MB"
}
