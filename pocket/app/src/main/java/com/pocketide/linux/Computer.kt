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

/** A directory made visible inside one proot session. */
data class Bind(val hostPath: String, val guestPath: String, val readOnly: Boolean = false)

/**
 * One program to run inside Linux. The environment is exactly [env] plus the fixed basics
 * (HOME, PATH, LANG, TERM, TZ, TMPDIR): proot runs `env -i`, so nothing from Android leaks in.
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

    /** Size on disk of the rootfs, for "Your data". */
    fun sizeBytes(): Long
}
