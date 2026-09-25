package com.pocketide.ui.manage

import com.pocketide.model.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SessionUsageTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    // Thursday 24 September 2026, 18:00 on the phone.
    private val now = at(2026, 9, 24, 18)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun session(
        id: String,
        agentId: String,
        startedAt: Long,
        lastActivityAt: Long,
        tokensIn: Long = 0,
        tokensOut: Long = 0,
        conflictOf: String? = null,
    ) = SessionRecord(
        id = id, agentId = agentId, projectId = "me/app", title = id, branch = "pocket/$agentId/x",
        startedAt = startedAt, lastActivityAt = lastActivityAt, deviceId = "d",
        tokensIn = tokensIn, tokensOut = tokensOut, conflictOf = conflictOf,
    )

    @Test
    fun `periods start at the phone's midnight, on Monday, and on the first`() {
        assertEquals(at(2026, 9, 24, 0), SessionUsage.start(UsagePeriod.TODAY, now, zone))
        assertEquals(at(2026, 9, 21, 0), SessionUsage.start(UsagePeriod.WEEK, now, zone))
        assertEquals(at(2026, 9, 1, 0), SessionUsage.start(UsagePeriod.MONTH, now, zone))
    }

    @Test
    fun `sessions, time and tokens add up per agent and period`() {
        val sessions = listOf(
            // Today, 2 hours, with tokens.
            session("a", "claude", at(2026, 9, 24, 9), at(2026, 9, 24, 11), tokensIn = 1_000, tokensOut = 200),
            // Started yesterday, still going this morning: only today's part counts for today.
            session("b", "claude", at(2026, 9, 23, 22), at(2026, 9, 24, 1), tokensIn = 500, tokensOut = 50),
            // Earlier this month, no tokens recorded.
            session("c", "codex", at(2026, 9, 2, 10), at(2026, 9, 2, 10, 30)),
            // Last month: outside every period.
            session("d", "codex", at(2026, 8, 20, 10), at(2026, 8, 20, 12), tokensIn = 9_999),
            // A conflict copy repeats session a; it is not counted twice.
            session("a2", "claude", at(2026, 9, 24, 9), at(2026, 9, 24, 11), tokensIn = 1_000, conflictOf = "a"),
        )
        val periods = SessionUsage.periods(sessions, now, zone).associateBy { it.period }

        val today = periods.getValue(UsagePeriod.TODAY)
        assertEquals(listOf("claude"), today.agents.map { it.agentId })
        val claudeToday = today.agents.single()
        assertEquals(1, claudeToday.started)
        assertEquals(2, claudeToday.active)
        assertEquals(3 * 3_600_000L, claudeToday.timeMs)
        assertEquals(1_500L, claudeToday.tokensIn)
        assertEquals(250L, claudeToday.tokensOut)

        val week = periods.getValue(UsagePeriod.WEEK).agents.single()
        assertEquals(2, week.started)
        assertEquals(5 * 3_600_000L, week.timeMs)

        val month = periods.getValue(UsagePeriod.MONTH)
        assertEquals(listOf("claude", "codex"), month.agents.map { it.agentId })
        val codex = month.agents.last()
        assertEquals(30 * 60_000L, codex.timeMs)
        assertEquals(false, codex.tokensKnown)
        assertEquals(3, month.active)
    }

    @Test
    fun `lines say what is known and leave unrecorded tokens out`() {
        val withTokens = AgentUsage("claude", started = 2, active = 3, timeMs = 65 * 60_000L, tokensIn = 12_000, tokensOut = 3_400)
        assertEquals("2 new sessions, 1 continued · 1 h 5 min · 12K tokens in · 3.4K out", SessionUsage.line(withTokens))
        val noTokens = AgentUsage("codex", started = 1, active = 1, timeMs = 30_000, tokensIn = 0, tokensOut = 0)
        val line = SessionUsage.line(noTokens)
        assertEquals("1 new session · under 1 min", line)
        assertTrue(!line.contains("token"))
        assertEquals("1 continued session · 2 h", SessionUsage.line(noTokens.copy(started = 0, timeMs = 2 * 3_600_000L)))
    }
}
