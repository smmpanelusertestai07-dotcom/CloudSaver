package com.pocketide.ui.manage

import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.schedule.ScheduledTask
import com.pocketide.secrets.ProjectValue
import com.pocketide.secrets.SecretKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormsTest {
    @Test
    fun `value names follow environment and GitHub rules`() {
        assertNull(ValueNames.problem("API_BASE_URL", SecretKind.VARIABLE))
        assertNull(ValueNames.problem("_private2", SecretKind.SECRET))
        assertNotNull(ValueNames.problem("", SecretKind.VARIABLE))
        assertNotNull(ValueNames.problem("2FAST", SecretKind.VARIABLE))
        assertNotNull(ValueNames.problem("MY-KEY", SecretKind.VARIABLE))
        assertNotNull(ValueNames.problem("with space", SecretKind.SECRET))
        assertNotNull(ValueNames.problem("A".repeat(ValueNames.MAX_LENGTH + 1), SecretKind.SECRET))
    }

    @Test
    fun `GITHUB_ is refused for Secrets, the computer's own names for Variables`() {
        assertNotNull(ValueNames.problem("github_token", SecretKind.SECRET))
        assertNull(ValueNames.problem("GITHUB_ORG", SecretKind.VARIABLE))
        assertNotNull(ValueNames.problem("PATH", SecretKind.VARIABLE))
        assertNotNull(ValueNames.problem("home", SecretKind.VARIABLE))
        assertNull(ValueNames.problem("PATH", SecretKind.SECRET))
    }

    @Test
    fun `names compare without case and screens see their own scope`() {
        assertTrue(ValueNames.sameName("Api_Key", " API_KEY "))
        val values = listOf(
            ProjectValue("me/app", "ZED", SecretKind.SECRET, 1, false),
            ProjectValue("me/app", "alpha", SecretKind.VARIABLE, 1, false),
            ProjectValue(null, "GLOBAL", SecretKind.VARIABLE, 1, false),
            ProjectValue("me/other", "OTHER", SecretKind.VARIABLE, 1, false),
        )
        assertEquals(listOf("alpha", "ZED"), ValueNames.scoped(values, "me/app").map { it.name })
        assertEquals(listOf("GLOBAL"), ValueNames.scoped(values, null).map { it.name })
    }

    @Test
    fun `schedule form explains the first problem`() {
        assertEquals("Choose a project.", ScheduleForm.problem("t", "p", 24, null, "claude"))
        assertEquals("Choose an agent.", ScheduleForm.problem("t", "p", 24, "me/app", ""))
        assertEquals("Give the task a short title.", ScheduleForm.problem(" ", "p", 24, "me/app", "claude"))
        assertEquals("Write what the agent should do.", ScheduleForm.problem("t", "", 24, "me/app", "claude"))
        assertNotNull(ScheduleForm.problem("t", "p", 0, "me/app", "claude"))
        assertNotNull(ScheduleForm.problem("t", "p", null, "me/app", "claude"))
        assertNotNull(ScheduleForm.problem("t", "p", ScheduleForm.MAX_HOURS + 1, "me/app", "claude"))
        assertNull(ScheduleForm.problem("Nightly tests", "Run the tests", 24, "me/app", "claude"))
    }

    @Test
    fun `next run follows the last one, or is due now, or never when off`() {
        val task = ScheduledTask("t1", "me/app", "claude", "t", "p", everyHours = 6)
        assertEquals(1_000L, ScheduleForm.nextDueAt(task, 1_000L))
        assertEquals(500L + 6 * 3_600_000L, ScheduleForm.nextDueAt(task.copy(lastRunAt = 500L), 1_000L))
        assertNull(ScheduleForm.nextDueAt(task.copy(enabled = false), 1_000L))
    }

    private fun session(id: String, chat: Long, media: Long, status: SessionStatus = SessionStatus.OPEN, count: Int = 0) = SessionRecord(
        id = id, agentId = "claude", projectId = "me/app", title = id, branch = "pocket/claude/x", startedAt = 0,
        lastActivityAt = 0, status = status, transcriptBytes = chat, mediaBytes = media, mediaCount = count, deviceId = "d",
    )

    @Test
    fun `largest sessions skip deleted and empty ones`() {
        val sessions = listOf(
            session("small", 10, 0),
            session("big", 100, 900, count = 3),
            session("gone", 5_000, 0, SessionStatus.DELETED),
            session("empty", 0, 0),
            session("mid", 500, 0),
        )
        assertEquals(listOf("big", "mid", "small"), DataMath.largestSessions(sessions).map { it.id })
        assertEquals(listOf("big"), DataMath.largestSessions(sessions, limit = 1).map { it.id })
        assertEquals(610L, DataMath.chatBytes(sessions))
        assertEquals(900L, DataMath.mediaBytes(sessions))
        assertEquals(3, DataMath.mediaCount(sessions))
    }

    @Test
    fun `delete everything needs the exact word`() {
        assertTrue(DataMath.deleteConfirmed("DELETE"))
        assertTrue(DataMath.deleteConfirmed(" DELETE "))
        assertFalse(DataMath.deleteConfirmed("delete"))
        assertFalse(DataMath.deleteConfirmed("DELET"))
        assertFalse(DataMath.deleteConfirmed("DELETE​"))
        assertFalse(DataMath.deleteConfirmed("ＤＥＬＥＴＥ"))
    }

    @Test
    fun `sizes by place leave Drive out until it is counted`() {
        assertEquals("12 MB here", DataMath.places(12_000_000, null))
        assertEquals("12 MB here · 0 B in Drive", DataMath.places(12_000_000, 0))
        assertEquals("0 B here · 4 MB in Drive", DataMath.places(0, 4_000_000))
    }
}
