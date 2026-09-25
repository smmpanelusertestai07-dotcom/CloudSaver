package com.pocketide.ui.manage

import com.pocketide.limiter.RoomWork
import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.rooms.RoomStop
import com.pocketide.rooms.StopReason
import com.pocketide.ui.components.Tone

/** What the Activity and Home screens say about rooms that stopped, sleep soon, or work. */
object WorkText {
    /** A room's idle sleep is worth a chip only when it is this close. */
    const val SLEEP_SOON_MS: Long = 10 * 60_000L

    /**
     * The session each stopped room opens on again: the one it showed, else the agent's latest
     * open session. Agents with no open session are left out.
     */
    fun resumeTargets(agentIds: List<String>, sessions: List<SessionRecord>, active: (String) -> String?): List<Pair<String, String>> =
        agentIds.distinct().mapNotNull { agentId ->
            val open = sessions.filter { it.agentId == agentId && it.status == SessionStatus.OPEN && it.deletedAt == null }
            val sessionId = active(agentId)?.takeIf { id -> open.any { it.id == id } }
                ?: open.maxByOrNull { it.lastActivityAt }?.id
            sessionId?.let { agentId to it }
        }

    /** "working" while something holds the room, and the sleep chip in its last ten minutes. */
    fun chips(name: String, work: RoomWork?, now: Long): List<Told> {
        work ?: return emptyList()
        val chips = mutableListOf<Told>()
        if (work.busy.isNotEmpty()) chips += Told("working", Tone.OK)
        val left = work.sleepsAt?.minus(now)
        if (left != null && left < SLEEP_SOON_MS) {
            val minutes = ((left + 59_999) / 60_000).coerceAtLeast(1)
            chips += Told("$name room sleeps in $minutes min", Tone.WARN)
        }
        return chips
    }

    /**
     * Stops worth a banner: the owner's own Stop is not news, and a stop older than the room's
     * current start is cleared by the rooms module itself.
     */
    fun newsworthy(stops: Map<String, RoomStop>): Map<String, RoomStop> = stops.filterValues { it.reason != StopReason.OWNER }
}
