package com.pocketide.ui.manage

import com.pocketide.core.Settings
import com.pocketide.model.Guard
import com.pocketide.rooms.RoomState
import com.pocketide.sync.MoveState
import com.pocketide.sync.SyncStatus
import com.pocketide.ui.components.Tone

/** A sentence with the colour it is shown in. */
data class Told(val text: String, val tone: Tone)

/** Plain words for states the modules report. */
object ManageText {
    fun guard(guard: Guard): Told = when (guard) {
        Guard.OK -> Told("All clear. Agents, builds and downloads can start.", Tone.OK)
        Guard.NO_NEW_HEAVY -> Told("Battery is low or the phone is warm. New builds, installs and agents wait.", Tone.WARN)
        Guard.NO_NEW_AGENTS -> Told("Memory is tight. An idle agent closes before another one starts.", Tone.WARN)
        Guard.PAUSE -> Told("Battery is very low or the phone is hot. The current step finishes, chats sync, then work pauses.", Tone.ERROR)
        Guard.SAFE_STOP -> Told("Battery is almost empty or the phone is too hot. Work is stopping safely now.", Tone.ERROR)
    }

    fun sync(status: SyncStatus, time: (Long) -> String, bytes: (Long) -> String): Told = when (status) {
        SyncStatus.Idle -> Told("Waiting for the next sync.", Tone.NEUTRAL)
        is SyncStatus.Running -> Told("Syncing: ${status.what}", Tone.OK)
        is SyncStatus.UpToDate -> Told("Up to date, as of ${time(status.at)}.", Tone.OK)
        is SyncStatus.Waiting -> Told(
            "${status.why.trimEnd('.')}. ${bytes(status.pendingBytes)} waits safely on this phone since ${time(status.since)}.",
            Tone.WARN,
        )
        is SyncStatus.Error -> Told(status.why, Tone.ERROR)
    }

    fun room(state: RoomState, bytes: (Long) -> String): Told = when (state) {
        RoomState.Stopped -> Told("Stopped", Tone.NEUTRAL)
        is RoomState.Starting -> Told("Starting: ${state.step}", Tone.WARN)
        is RoomState.Running -> Told(if (state.memoryBytes > 0) "Running · ${bytes(state.memoryBytes)}" else "Running", Tone.OK)
        is RoomState.Failed -> Told(state.why, Tone.ERROR)
    }

    /** Where "Move to another Google account" stands; null when no move is going on. */
    fun move(state: MoveState): Told? = when (state) {
        MoveState.Idle -> null
        is MoveState.NeedsConsent -> Told("Choose the new account in Google's window.", Tone.WARN)
        is MoveState.Copying -> Told(
            if (state.total == 0) "Getting ready to copy to ${state.to}…" else "Copying to ${state.to}: ${state.done} of ${state.total} files.",
            Tone.WARN,
        )
        is MoveState.ReadyToEraseOld -> Told(
            "Everything is in ${state.to} and checked. The copy in ${state.from} is still there until you erase it.",
            Tone.OK,
        )
        is MoveState.Done -> Told("Everything is in ${state.to}.", Tone.OK)
        is MoveState.Failed -> Told(state.why.ifBlank { PlainError.GENERIC }, Tone.ERROR)
    }

    /** 0..1 while files are copied, else null. */
    fun moveProgress(state: MoveState): Float? =
        (state as? MoveState.Copying)?.takeIf { it.total > 0 }?.let { (it.done.toFloat() / it.total).coerceIn(0f, 1f) }

    /** The retention choices of §6.8, one line each. */
    fun retention(s: Settings): List<Pair<String, String>> = listOf(
        "Chats in Drive" to if (s.keepChatsMonths <= 0) "Until you delete them" else "${s.keepChatsMonths} months after the last message",
        "Recently deleted" to "30 days, then erased from Drive",
        "Chats on this phone" to if (s.phoneChatDays < 0) "Keep all" else "${s.phoneChatDays} days after Drive has them",
        "Media on this phone" to if (s.phoneMediaDays < 0) "Keep all" else "${s.phoneMediaDays} days (Drive keeps the original)",
        "Project caches" to "${s.cacheDays} days unused",
        "Unused computer" to if (s.computerUnusedDays < 0) "Never removed" else "Removed after ${s.computerUnusedDays} days without agent work",
    )
}
