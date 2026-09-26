package com.pocketide.limiter

import com.pocketide.linux.ComputerState
import com.pocketide.rooms.RemoteControlState
import com.pocketide.rooms.RoomState

/**
 * What the engine's service keeps alive, and what its notification says: the rooms that run,
 * Google's Remote Control where it is on (its daemon runs with no room open), and the
 * computer's own long work (set-up, reset, repair, an update), which must outlive the screen
 * that started it as much as any room.
 */
internal data class EngineLoad(val rooms: List<Line>, val computerWork: String?) {

    data class Line(val name: String, val working: Boolean, val starting: Boolean, val remoteControl: Boolean = false)

    /** Nothing left to keep alive: the service may go. */
    val idle: Boolean get() = rooms.isEmpty() && computerWork == null

    /** The CPU is held while an agent works or the computer is being set up or changed. */
    val working: Boolean get() = computerWork != null || rooms.any { it.working }

    val title: String get() = if (rooms.isEmpty() && computerWork != null) "Working on the computer" else "Computer running"

    val text: String
        get() {
            val parts = listOfNotNull(computerWork?.let { "Computer: $it" }) + rooms.map { line ->
                "${line.name}${if (line.remoteControl) " Remote Control" else ""}: ${when {
                    line.starting -> "starting"
                    line.remoteControl -> "on"
                    line.working -> "working"
                    else -> "ready"
                }}"
            }
            return parts.joinToString(" · ").ifEmpty { "Starting the computer…" }
        }

    companion object {
        /**
         * The load of [states] (rooms), [work] (what each room is busy with, by agent id),
         * [remoteControls] and [computer], each agent named by [name].
         */
        fun of(
            states: Map<String, RoomState>,
            work: Map<String, Boolean>,
            remoteControls: Map<String, RemoteControlState>,
            computer: ComputerState,
            name: (String) -> String,
        ): EngineLoad {
            val rooms = running(states).sorted().map { id ->
                Line(name(id), working = work[id] == true, starting = states[id] is RoomState.Starting)
            }
            val remote = remoteControls.filterValues { it !is RemoteControlState.Off }.keys.sorted().map { id ->
                Line(name(id), working = false, starting = remoteControls[id] == RemoteControlState.Starting, remoteControl = true)
            }
            return EngineLoad(rooms + remote, computerWork(computer))
        }

        /** Rooms that run or are starting, by agent id. */
        fun running(states: Map<String, RoomState>): Set<String> =
            states.filterValues { it is RoomState.Running || it is RoomState.Starting }.keys

        /** The computer's long work in a few words, or null when there is none. */
        fun computerWork(state: ComputerState): String? = when (state) {
            is ComputerState.Installing -> state.fraction?.let { "${state.step} ${(it.coerceIn(0f, 1f) * 100).toInt()}%" } ?: state.step
            is ComputerState.Updating -> state.what
            ComputerState.NotInstalled, ComputerState.Ready, is ComputerState.Broken -> null
        }
    }
}
