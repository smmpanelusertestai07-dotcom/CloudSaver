package com.pocketide.linux

import kotlinx.coroutines.flow.StateFlow

/** The Linux computer's life cycle, as the screens show it. */
sealed interface ComputerState {
    data object NotInstalled : ComputerState
    /** [step] is a short sentence; [fraction] is 0..1 or null when unknown. */
    data class Installing(val step: String, val fraction: Float?, val bytesDone: Long, val bytesTotal: Long) : ComputerState
    data object Ready : ComputerState
    data class Updating(val what: String) : ComputerState
    /** Something is wrong; [fix] says what the owner can do (usually "Reset computer"). */
    data class Broken(val why: String, val fix: String) : ComputerState
}

/**
 * A directory made visible inside one proot session.
 *
 * proot has no read-only binds, so [readOnly] cannot be honoured: a command that asks for one
 * is refused rather than started with a folder it could write to after all.
 */
data class Bind(val hostPath: String, val guestPath: String, val readOnly: Boolean = false)

/**
 * One program to run inside Linux. The environment is exactly [env] plus the fixed basics
 * (HOME, USER, LOGNAME, SHELL, PATH, TERM, LANG, TZ, TMPDIR): proot runs `env -i`, so nothing
 * from Android leaks in. [env] names must look like `[A-Z_][A-Z0-9_]*`; a name that repeats a
 * basic replaces it. [env] never appears on a command line (other processes can read those):
 * it reaches the program through a private file that is deleted once read. [argv] is visible
 * to every process inside Linux, so a launch token belongs in [env] or in a file, never there.
 */
data class LinuxCommand(
    val argv: List<String>,
    val binds: List<Bind> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val workDir: String = "/root",
    /** Merge stderr into stdout. */
    val mergeErrors: Boolean = true,
)

/** What the Computer screen shows. */
data class ComputerInfo(
    val cpu: String,
    val cores: Int,
    val kernel: String,
    val android: String,
    val ubuntu: String?,
    val codeServer: String?,
    val agy: String?,
    val rootfsBytes: Long,
    val vaBits: Int?,
)

/** A code-server release for linux-arm64, used only when its SHA-256 matches. */
data class CodeServerPin(val version: String, val url: String, val sha256: String, val bytes: Long)

/** What an update of one part of the computer did, in words the owner can read. */
sealed interface UpdateOutcome {
    data object UpToDate : UpdateOutcome
    data class Updated(val detail: String) : UpdateOutcome
    /** Not now (Wi-Fi, agents still running, not set up yet); it is tried again later. */
    data class Waiting(val why: String) : UpdateOutcome
    data class Failed(val why: String) : UpdateOutcome
}

/** How one item of a Repair turned out. */
enum class RepairStatus {
    /** Already right; nothing changed. */
    OK,

    /** Was missing or broken, and is now in place. */
    NEW,

    /** Could not be put right; [RepairItem.detail] says what to do. */
    WARN,

    /** Worth knowing; nothing to do. */
    NOTE,
}

/** One line of the Repair report. */
data class RepairItem(val what: String, val status: RepairStatus, val detail: String)

/**
 * What "Reset computer" deletes and what it keeps, in the words of its confirm sheet. The
 * computer's reset deletes exactly [removes]; a test holds the two together.
 */
data class ResetPlan(val removes: List<String>, val keeps: List<String>) {
    companion object {
        val STANDARD = ResetPlan(
            removes = listOf(
                "Ubuntu and everything installed into it: code-server, apt packages, tools agents added",
                "Downloads kept for set-up",
            ),
            keeps = listOf(
                "Each agent's sign-in, settings, instructions and chat history (they live in the room's home)",
                "Your projects, sessions and their unfinished work",
                "Variables, Secrets and Your data",
            ),
        )
    }
}

/** One host the app or the agents need, and whether the phone reached it. */
data class HostCheck(val host: String, val purpose: String, val ok: Boolean, val detail: String)

/**
 * The network check (Computer → Check network): each host the app and agents need, a name
 * lookup from inside Linux, and what on the phone stands in the way, in plain sentences.
 */
data class NetworkReport(
    val checkedAt: Long,
    val hosts: List<HostCheck>,
    /** Looking up a name from inside Linux; null when the computer is not set up. */
    val linuxDns: HostCheck?,
    /** What blocks or slows the connection (Private DNS, VPN, a sign-in page, Data Saver), with the fix. */
    val blockers: List<String>,
)

/**
 * The Ubuntu computer inside the app (proot, no root, no VM). Ported from PocketIDE 2.6.0's
 * engine: pinned, checksum-verified downloads; rebuilt identically by [reset]; an empty
 * environment every time.
 */
interface Computer {
    val state: StateFlow<ComputerState>

    /** Installs or repairs the computer. Big download: callers check the data rules first. */
    suspend fun install()

    /** Deletes and rebuilds the computer. Projects and chats are untouched (they live elsewhere). */
    suspend fun reset()

    /** Starts a process inside Linux. The caller owns it and must [stop] it. */
    fun start(command: LinuxCommand): Process

    /** Runs to completion, passing each output line on; returns the exit code. */
    suspend fun run(command: LinuxCommand, onLine: (String) -> Unit = {}): Int

    /** Ends a proot and everything it traces (SIGQUIT, then destroy). */
    fun stop(process: Process)

    suspend fun info(): ComputerInfo

    /** Size on disk of the rootfs, for "Your data". Walks the tree: call it off the main thread. */
    fun sizeBytes(): Long

    /**
     * Ubuntu's security fixes, and a set-up script changed by an app update. Needs Wi-Fi by
     * default; the daily job calls it.
     */
    suspend fun updateBase(): UpdateOutcome = UpdateOutcome.UpToDate

    /**
     * Moves code-server to [pin]: unpacked beside the current one, checked, switched over, and
     * switched back if it does not start. Waits while any room is running. Never moves to an
     * older version than the one in place.
     */
    suspend fun updateCodeServer(pin: CodeServerPin): UpdateOutcome = UpdateOutcome.UpToDate

    /** Linux processes this app runs right now (proot and everything under it). */
    fun liveProcesses(): Int = 0

    /** Processes of one program started with [start]: its proot and everything under it; 0 once it ended. */
    fun liveProcesses(process: Process): Int = 0

    /** Deletes the computer without setting it up again (the unused-computer rule). */
    suspend fun remove() = Unit

    /**
     * Fix-it level 3, "Restart the computer": every Linux program ends (each exactly, by its
     * process tree, never by name); files, sign-ins and chat history stay.
     */
    suspend fun restart() = Unit

    /**
     * Fix-it level 4, "Repair": the set-up again, safe to repeat. It installs what is missing,
     * updates what is there, never overwrites a file the owner changed, and reports each item.
     */
    suspend fun repair(): List<RepairItem> = emptyList()

    /** What [reset] deletes and keeps, for its confirm sheet. */
    fun resetPlan(): ResetPlan = ResetPlan.STANDARD

    /** Reaches every host the app and agents need, and names what blocks them. */
    suspend fun checkNetwork(): NetworkReport = NetworkReport(0, emptyList(), null, emptyList())
}
