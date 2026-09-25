package com.pocketide.rooms

import com.pocketide.core.Redact
import java.io.File

/** The last lines a room's programs printed, secrets removed, for diagnostics. */
internal class OutputRing(private val capacity: Int = 200) {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        val clean = Redact.text(line.take(MAX_LINE)).trimEnd()
        if (clean.isEmpty()) return
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(clean)
    }

    @Synchronized
    fun last(count: Int): List<String> = lines.toList().takeLast(count)

    private companion object {
        const val MAX_LINE = 500
    }
}

/**
 * CPU time and memory of one of this app's processes and everything under it, read from /proc.
 * proot starts every program of a room as its own child, so the tree is the room.
 */
internal class ProcFacts(private val proc: File = File("/proc")) {

    /** [pid] and all its descendants. */
    fun tree(pid: Int): List<Int> {
        val children = HashMap<Int, MutableList<Int>>()
        for (candidate in pids()) {
            val parent = statFields(candidate)?.getOrNull(PPID) ?: continue
            children.getOrPut(parent.toIntOrNull() ?: continue) { mutableListOf() } += candidate
        }
        val found = mutableListOf(pid)
        var i = 0
        while (i < found.size) {
            children[found[i]]?.forEach { child -> if (child !in found) found += child }
            i++
        }
        return found
    }

    /** User plus system clock ticks used so far by [pids]. */
    fun cpuTicks(pids: List<Int>): Long = pids.sumOf { pid ->
        val fields = statFields(pid) ?: return@sumOf 0L
        (fields.getOrNull(UTIME)?.toLongOrNull() ?: 0L) + (fields.getOrNull(STIME)?.toLongOrNull() ?: 0L)
    }

    /** Resident memory of [pids] in bytes. Shared pages count once per process, so it is an upper bound. */
    fun residentBytes(pids: List<Int>): Long = pids.sumOf { pid ->
        read(File(proc, "$pid/status"))?.lineSequence()
            ?.firstOrNull { it.startsWith("VmRSS:") }
            ?.removePrefix("VmRSS:")?.trim()?.substringBefore(' ')?.toLongOrNull()?.times(1024) ?: 0L
    }

    private fun pids(): List<Int> = proc.list()?.mapNotNull { it.toIntOrNull() }.orEmpty()

    /** Fields after the command name, which may itself hold spaces and brackets. */
    private fun statFields(pid: Int): List<String>? {
        val stat = read(File(proc, "$pid/stat")) ?: return null
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        return stat.substring(close + 1).trim().split(' ')
    }

    private fun read(file: File): String? = try {
        file.readText()
    } catch (gone: java.io.IOException) {
        null
    }

    companion object {
        // Positions after the ")" of /proc/<pid>/stat: state is 0, ppid 1, utime 11, stime 12.
        private const val PPID = 1
        private const val UTIME = 11
        private const val STIME = 12
        private val PID = Regex("""pid=(\d+)""")

        /** Android's Process has no pid(); its toString() carries it, as the JDK's does. */
        fun pidOf(process: Process): Int? = PID.find(process.toString())?.groupValues?.get(1)?.toIntOrNull()
    }
}

/**
 * When a room last did something. Work counts, not the screen: a turn in progress, a command
 * running, the terminal printing, or the owner using the room. CPU time is sampled; a room that
 * used less than [busyTicksPerSample] since the last sample was quiet in between. [idleLimitMs]
 * is read each time, so a changed idle time applies at once; 0 or less means it never sleeps.
 */
internal class ActivityClock(
    startedAt: Long,
    private val idleLimitMs: () -> Long,
    private val busyTicksPerSample: Long = BUSY_TICKS,
) {
    constructor(startedAt: Long, idleLimitMs: Long) : this(startedAt, { idleLimitMs })

    @Volatile
    var lastActivityAt: Long = startedAt
        private set
    private var lastTicks: Long? = null

    fun touch(now: Long) {
        if (now > lastActivityAt) lastActivityAt = now
    }

    /** Records a CPU sample; returns true when the room was busy since the previous one. */
    @Synchronized
    fun sample(now: Long, ticks: Long): Boolean {
        val previous = lastTicks
        lastTicks = ticks
        val busy = previous != null && ticks - previous >= busyTicksPerSample
        if (busy) touch(now)
        return busy
    }

    /** When the room sleeps if nothing happens, or null when idle sleep is off. */
    fun sleepsAt(): Long? = idleLimitMs().takeIf { it > 0 }?.let { lastActivityAt + it }

    fun isIdle(now: Long): Boolean = sleepsAt()?.let { now >= it } ?: false

    /** True when the room did something in the last [windowMs]. */
    fun busyWithin(now: Long, windowMs: Long): Boolean = now - lastActivityAt < windowMs

    companion object {
        /** One second of CPU in a sample: more than an idle engine uses, less than any real work. */
        const val BUSY_TICKS = 100L
    }
}

/**
 * What each room is busy with ("turn", "command", "build", "write"), passed to the limiter only
 * when it changes, so a room is never closed mid-work and the wake lock is held only then.
 * Holds of one kind count up and down: two builds at once keep the room busy until both end.
 */
internal class WorkHolds(private val report: (agentId: String, what: String, busy: Boolean) -> Unit) {
    private val counts = HashMap<Pair<String, String>, Int>()

    @Synchronized
    fun hold(agentId: String, what: String) {
        val key = agentId to what
        val count = counts[key] ?: 0
        counts[key] = count + 1
        if (count == 0) report(agentId, what, true)
    }

    @Synchronized
    fun release(agentId: String, what: String) {
        val key = agentId to what
        val count = counts[key] ?: return
        if (count > 1) {
            counts[key] = count - 1
        } else {
            counts.remove(key)
            report(agentId, what, false)
        }
    }

    /** Sets a kind of work that is sampled rather than counted (CPU use of the room's programs). */
    @Synchronized
    fun set(agentId: String, what: String, busy: Boolean) {
        val key = agentId to what
        when {
            busy && key !in counts -> {
                counts[key] = 1
                report(agentId, what, true)
            }
            !busy && counts.remove(key) != null -> report(agentId, what, false)
        }
    }

    /** Rooms that hold [what] now. */
    @Synchronized
    fun holding(what: String): Set<String> = counts.keys.filter { it.second == what }.mapTo(HashSet()) { it.first }

    /** The room stopped: whatever it held has ended. */
    @Synchronized
    fun clear(agentId: String) {
        for (key in counts.keys.filter { it.first == agentId }) {
            counts.remove(key)
            report(agentId, key.second, false)
        }
    }

    companion object {
        const val TURN = "turn"
        const val COMMAND = "command"
        const val BUILD = "build"
        const val WRITE = "write"
    }
}
