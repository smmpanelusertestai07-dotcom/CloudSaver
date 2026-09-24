package com.pocketide.ui.work

import com.pocketide.github.WorkflowRun
import com.pocketide.model.SessionStatus
import com.pocketide.rooms.RoomState
import com.pocketide.sessions.PutOnMainResult
import com.pocketide.ui.components.Tone
import com.pocketide.ui.screens.project.COMMON_DEV_PORTS
import com.pocketide.ui.screens.project.LARGE_TRANSCRIPT_BYTES
import com.pocketide.ui.screens.project.RoomView
import com.pocketide.ui.screens.project.WorkFormat
import com.pocketide.ui.screens.project.defaultSession
import com.pocketide.ui.screens.project.describePutOnMain
import com.pocketide.ui.screens.project.isLargeTranscript
import com.pocketide.ui.screens.project.isoToEpoch
import com.pocketide.ui.screens.project.previewPorts
import com.pocketide.ui.screens.project.roomView
import com.pocketide.ui.screens.project.runStatus
import com.pocketide.ui.screens.project.sessionForBranch
import com.pocketide.ui.screens.project.sessionStatusLabel
import com.pocketide.ui.screens.project.sessionsByAgent
import com.pocketide.ui.screens.project.waitingVideosText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectLogicTest {
    @Test
    fun bytesInPlainUnits() {
        assertEquals("0 B", WorkFormat.bytes(0))
        assertEquals("0 B", WorkFormat.bytes(-5))
        assertEquals("1023 B", WorkFormat.bytes(1023))
        assertEquals("1.0 KB", WorkFormat.bytes(1024))
        assertEquals("1.5 MB", WorkFormat.bytes(1024L * 1024 * 3 / 2))
        assertEquals("272 MB", WorkFormat.bytes(272L * 1024 * 1024))
        assertEquals("2.5 GB", WorkFormat.bytes(2560L * 1024 * 1024))
        assertEquals("1 session", WorkFormat.count(1, "session", "sessions"))
        assertEquals("0 sessions", WorkFormat.count(0, "session", "sessions"))
    }

    @Test
    fun previewPortsPutTheAgentFirstAndSkipAppPorts() {
        assertEquals(listOf(5173, 3000, 8000), previewPorts(listOf(5173), setOf(3000, 8000, 5173), emptySet()))
        assertEquals(listOf(9000, 3000), previewPorts(listOf(9000, 9000, 0, 70000), setOf(3000), emptySet()))
        assertEquals(listOf(3000), previewPorts(emptyList(), setOf(3000, 8080), excluded = setOf(8080)))
        assertEquals(emptyList<Int>(), previewPorts(listOf(8080), emptySet(), excluded = setOf(8080)))
        // Only the common dev ports are probed; a stray open port is not offered.
        assertEquals(emptyList<Int>(), previewPorts(emptyList(), setOf(22), emptySet()))
        assertEquals(listOf(3000, 3001, 4200, 5000, 5173, 8000, 8080, 8888), COMMON_DEV_PORTS)
    }

    @Test
    fun sessionsGroupByAgentNewestFirst() {
        val list = listOf(
            session("a", agentId = "claude", lastActivityAt = 10),
            session("b", agentId = "codex", lastActivityAt = 30),
            session("c", agentId = "claude", lastActivityAt = 20),
            session("d", agentId = "codex", status = SessionStatus.DELETED, lastActivityAt = 99),
            session("e", agentId = "claude", projectId = "me/other", lastActivityAt = 50),
        )
        val groups = sessionsByAgent(list, "me/app")
        assertEquals(listOf("codex", "claude"), groups.map { it.first })
        assertEquals(listOf("b"), groups[0].second.map { it.id })
        assertEquals(listOf("c", "a"), groups[1].second.map { it.id })
    }

    @Test
    fun defaultSessionPrefersTheLatestOpenOne() {
        val list = listOf(
            session("merged", status = SessionStatus.ON_MAIN, lastActivityAt = 50),
            session("open-old", lastActivityAt = 10),
            session("open-new", lastActivityAt = 20),
        )
        assertEquals("open-new", defaultSession(list)?.id)
        assertEquals("merged", defaultSession(listOf(list[0]))?.id)
        assertNull(defaultSession(listOf(session("x", status = SessionStatus.DELETED))))
    }

    @Test
    fun runsFindTheirSessionByBranch() {
        val list = listOf(session("a", branch = "pocket/claude/x"), session("b", branch = "pocket/codex/y"))
        assertEquals("b", sessionForBranch(list, "pocket/codex/y")?.id)
        assertNull(sessionForBranch(list, "main"))
    }

    @Test
    fun waitingVideosWording() {
        assertNull(waitingVideosText(0))
        assertEquals("1 video waiting for Wi-Fi", waitingVideosText(1))
        assertEquals("3 videos waiting for Wi-Fi", waitingVideosText(3))
    }

    @Test
    fun statusLabels() {
        assertEquals("Running" to Tone.OK, sessionStatusLabel(SessionStatus.OPEN, running = true))
        assertEquals("Open" to Tone.NEUTRAL, sessionStatusLabel(SessionStatus.OPEN, running = false))
        assertEquals("On main" to Tone.OK, sessionStatusLabel(SessionStatus.ON_MAIN, running = false))
        assertEquals("Conflict copy" to Tone.WARN, sessionStatusLabel(SessionStatus.CONFLICT_COPY, running = false))
    }

    @Test
    fun putOnMainOutcomesAreInPlainWords() {
        assertEquals(Tone.OK, describePutOnMain(PutOnMainResult.Merged).tone)

        val conflicts = describePutOnMain(PutOnMainResult.Conflicts((1..10).map { "src/f$it.kt" }))
        assertEquals(Tone.WARN, conflicts.tone)
        assertTrue(conflicts.text.contains("src/f1.kt"))
        assertTrue(conflicts.text.contains("src/f8.kt"))
        assertFalse(conflicts.text.contains("src/f9.kt"))
        assertTrue(conflicts.text.contains("and 2 more"))
        assertTrue(conflicts.text.contains("tap Put on main again"))

        val blocked = describePutOnMain(PutOnMainResult.Blocked("A secret was found in .env."))
        assertEquals("Stopped at the check-post", blocked.title)
        assertTrue(blocked.text.startsWith("A secret was found in .env. Nothing was pushed."))

        val failed = describePutOnMain(PutOnMainResult.Failed("No network"))
        assertEquals(Tone.ERROR, failed.tone)
        assertEquals("No network. Nothing changed on main.", failed.text)
    }

    @Test
    fun runStatusInPlainWords() {
        fun run(status: String, conclusion: String?) = WorkflowRun(1, "Android", "main", status, conclusion, "", "", "")
        assertEquals("Succeeded" to Tone.OK, runStatus(run("completed", "success")))
        assertEquals("Failed" to Tone.ERROR, runStatus(run("completed", "failure")))
        assertEquals("Cancelled" to Tone.NEUTRAL, runStatus(run("completed", "cancelled")))
        assertEquals("Running" to Tone.OK, runStatus(run("in_progress", null)))
        assertEquals("Waiting for a runner" to Tone.WARN, runStatus(run("queued", null)))
        assertEquals("Odd" to Tone.NEUTRAL, runStatus(run("odd", null)))
    }

    @Test
    fun isoTimes() {
        assertEquals(1_758_700_800_000L, isoToEpoch("2025-09-24T08:00:00Z"))
        assertNull(isoToEpoch("yesterday"))
        assertNull(isoToEpoch(null))
    }

    @Test
    fun largeTranscriptThreshold() {
        assertFalse(isLargeTranscript(session("a", transcriptBytes = LARGE_TRANSCRIPT_BYTES - 1)))
        assertTrue(isLargeTranscript(session("a", transcriptBytes = LARGE_TRANSCRIPT_BYTES)))
    }

    @Test
    fun roomViewCombinesOpenAndLiveState() {
        val running = RoomState.Running("http://127.0.0.1:5000/", "s1", 0)
        assertEquals(RoomView.Opening(null), roomView(null, null, "s1"))
        assertEquals(RoomView.Opening("Starting the computer"), roomView(null, RoomState.Starting("Starting the computer"), "s1"))
        assertEquals(RoomView.Ready(running.url), roomView(null, running, "s1"))
        assertEquals(RoomView.Ready(running.url), roomView(running, null, "s1"))
        // Another session is still showing while this one opens: keep waiting, then say so.
        assertEquals(RoomView.Opening(null), roomView(null, running, "s2"))
        assertEquals(RoomView.Elsewhere, roomView(running, running, "s2"))
        // A failure from before a retry is not shown while the retry runs.
        assertEquals(RoomView.Opening(null), roomView(null, RoomState.Failed("old"), "s1"))
        assertEquals(RoomView.Failed("no memory"), roomView(RoomState.Failed("no memory"), running, "s1"))
        assertEquals(RoomView.Failed("crashed"), roomView(running, RoomState.Failed("crashed"), "s1"))
        assertEquals(RoomView.Stopped, roomView(running, RoomState.Stopped, "s1"))
        assertEquals(RoomView.Opening(null), roomView(null, RoomState.Stopped, "s1"))
    }
}
