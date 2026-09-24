package com.pocketide.linux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Sends a signal to one of this app's own processes. */
internal fun interface Signals {
    fun send(pid: Int, signal: Int)

    companion object {
        const val SIGKILL = 9
        const val SIGQUIT = 3
    }
}

/** The processes /proc shows this app: its own and their children. */
internal class ProcTable(private val proc: File = File("/proc")) {

    /** Processes running as [uid], apart from [selfPid] (the app itself). */
    fun countOwnedBy(uid: Int, selfPid: Int): Int = pids().count { pid ->
        pid != selfPid && uidOf(pid) == uid
    }

    /** Every process under [pid], children after their own children, so they can be ended bottom-up. */
    fun descendants(pid: Int): List<Int> {
        val children = HashMap<Int, MutableList<Int>>()
        for (candidate in pids()) {
            val parent = parentOf(candidate) ?: continue
            children.getOrPut(parent) { mutableListOf() } += candidate
        }
        val ordered = mutableListOf<Int>()
        val seen = hashSetOf(pid)
        fun visit(parent: Int) {
            for (child in children[parent].orEmpty()) {
                if (!seen.add(child)) continue
                visit(child)
                ordered += child
            }
        }
        visit(pid)
        return ordered
    }

    private fun pids(): List<Int> = proc.list()?.mapNotNull { name -> name.toIntOrNull()?.takeIf { it > 0 } }.orEmpty()

    private fun uidOf(pid: Int): Int? = read(File(proc, "$pid/status"))
        ?.lineSequence()
        ?.firstOrNull { it.startsWith("Uid:") }
        ?.removePrefix("Uid:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()

    /** Field 4 of /proc/<pid>/stat, read after the last ")" because a program name may contain spaces or brackets. */
    private fun parentOf(pid: Int): Int? {
        val stat = read(File(proc, "$pid/stat")) ?: return null
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        return stat.substring(close + 1).trim().split(' ').getOrNull(1)?.toIntOrNull()
    }

    /** A process that ends between the listing and the read is ordinary, not an error. */
    private fun read(file: File): String? = runCatching { file.readText() }.getOrNull()
}

/**
 * The proot processes this app started, and how they are ended.
 *
 * proot answers SIGTERM with nothing: only SIGQUIT (and fatal signals) make it kill every
 * process it traces and leave, which is why Process.destroy() alone left programs running.
 * So SIGQUIT first; if proot is still there after a grace period, its whole tree is killed
 * bottom-up, children before proot, so nothing is left running untraced.
 */
internal class ProcessKeeper(
    private val signals: Signals,
    private val table: ProcTable,
    private val scope: CoroutineScope,
    private val graceMs: Long = 3_000,
) {
    private val live: MutableSet<Process> = ConcurrentHashMap.newKeySet()

    fun track(process: Process) {
        live.removeAll { !it.isAlive }
        live += process
    }

    fun running(): Int = live.count { it.isAlive }

    fun stop(process: Process) {
        live.remove(process)
        if (!process.isAlive) return
        val pid = pidOf(process)
        if (pid == null) {
            process.destroyForcibly()
            return
        }
        signals.send(pid, Signals.SIGQUIT)
        scope.launch {
            delay(graceMs)
            if (process.isAlive) {
                table.descendants(pid).forEach { signals.send(it, Signals.SIGKILL) }
                process.destroyForcibly()
            }
        }
    }

    fun stopAll() = live.toList().forEach(::stop)

    companion object {
        private val PID = Regex("""pid=(\d+)""")

        /** Android's Process has no pid(); both Android's and the JDK's toString() carry it. */
        fun pidOf(process: Process): Int? = PID.find(process.toString())?.groupValues?.get(1)?.toIntOrNull()
    }
}

/**
 * Passes every output line of [process] to [onLine] and returns its exit code. If the caller is
 * cancelled, the process is stopped, so nothing keeps running for an answer nobody waits for.
 */
internal suspend fun drain(process: Process, mergedErrors: Boolean, onLine: (String) -> Unit, stop: (Process) -> Unit): Int =
    coroutineScope {
        try {
            val output = launch(Dispatchers.IO) { process.inputStream.use { it.forEachLine(onLine = onLine) } }
            // Unread errors would fill the pipe and stall the program.
            val errors = if (mergedErrors) null else launch(Dispatchers.IO) { process.errorStream.use { it.forEachLine { } } }
            val code = runInterruptible(Dispatchers.IO) { process.waitFor() }
            output.join()
            errors?.join()
            code
        } finally {
            if (process.isAlive) stop(process)
        }
    }
