package com.pocketide.ui.manage

import com.pocketide.model.SessionRecord
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** The periods the Activity screen adds sessions up for, each starting at midnight on the phone. */
enum class UsagePeriod(val label: String) {
    TODAY("Today"),
    WEEK("This week"),
    MONTH("This month"),
}

/** One agent's sessions in a period. */
data class AgentUsage(
    val agentId: String,
    /** Sessions that started in the period. */
    val started: Int,
    /** Sessions with activity in the period, whenever they started. */
    val active: Int,
    /** Time from start to last activity, counted only inside the period. */
    val timeMs: Long,
    /** Whole-session totals, where the agent records tokens (0 when it does not). */
    val tokensIn: Long,
    val tokensOut: Long,
) {
    val tokensKnown: Boolean get() = tokensIn > 0 || tokensOut > 0
}

data class PeriodUsage(val period: UsagePeriod, val from: Long, val agents: List<AgentUsage>) {
    val active: Int get() = agents.sumOf { it.active }
    val timeMs: Long get() = agents.sumOf { it.timeMs }
}

/** Sessions, time and tokens across all sessions (§8c's activity dashboard). */
object SessionUsage {
    fun periods(sessions: List<SessionRecord>, now: Long, zone: ZoneId): List<PeriodUsage> =
        UsagePeriod.entries.map { period ->
            val from = start(period, now, zone)
            PeriodUsage(period, from, byAgent(sessions, from, now))
        }

    fun start(period: UsagePeriod, now: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val first = when (period) {
            UsagePeriod.TODAY -> today
            UsagePeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            UsagePeriod.MONTH -> today.withDayOfMonth(1)
        }
        return first.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Per agent, most time first. A conflict copy repeats another session's chat, so it is not counted twice. */
    fun byAgent(sessions: List<SessionRecord>, from: Long, now: Long): List<AgentUsage> =
        sessions.asSequence()
            .filter { it.conflictOf == null && it.lastActivityAt >= from && it.startedAt <= now }
            .groupBy { it.agentId }
            .map { (agentId, list) ->
                AgentUsage(
                    agentId = agentId,
                    started = list.count { it.startedAt >= from },
                    active = list.size,
                    timeMs = list.sumOf { (minOf(it.lastActivityAt, now) - maxOf(it.startedAt, from)).coerceAtLeast(0) },
                    tokensIn = list.sumOf { it.tokensIn },
                    tokensOut = list.sumOf { it.tokensOut },
                )
            }
            .sortedWith(compareByDescending<AgentUsage> { it.timeMs }.thenBy { it.agentId })

    /** "2 new sessions, 1 continued · 1 h 5 min · 12K tokens in · 3K out"; tokens only where recorded. */
    fun line(usage: AgentUsage): String {
        val continued = usage.active - usage.started
        val sessions = when {
            continued == 0 -> ManageFormat.count(usage.started, "new session")
            usage.started == 0 -> ManageFormat.count(continued, "continued session")
            else -> "${ManageFormat.count(usage.started, "new session")}, $continued continued"
        }
        val tokens = if (usage.tokensKnown) {
            "${ManageFormat.compact(usage.tokensIn)} tokens in · ${ManageFormat.compact(usage.tokensOut)} out"
        } else {
            null
        }
        return listOfNotNull(sessions, ManageFormat.duration(usage.timeMs), tokens).joinToString(" · ")
    }
}
