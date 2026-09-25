package com.pocketide.ui.work

import com.pocketide.github.WorkflowRun
import com.pocketide.model.SessionStatus
import com.pocketide.rooms.RoomState
import com.pocketide.sessions.PutOnMainResult
import com.pocketide.ui.components.Tone
import com.pocketide.sessions.ChangedFile
import com.pocketide.sessions.SessionChanges
import com.pocketide.ui.screens.project.COMMON_DEV_PORTS
import com.pocketide.ui.screens.project.PreviewPort
import com.pocketide.ui.screens.project.Reach
import com.pocketide.ui.screens.project.Trust
import com.pocketide.ui.screens.project.autoOpenPort
import com.pocketide.ui.screens.project.canPutOnMain
import com.pocketide.ui.screens.project.changeTotals
import com.pocketide.ui.screens.project.finishedRun
import com.pocketide.ui.screens.project.followedFirst
import com.pocketide.ui.screens.project.keepRunners
import com.pocketide.ui.screens.project.needsPolling
import com.pocketide.ui.screens.project.pollDelayMs
import com.pocketide.ui.screens.project.trustOf
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
import com.pocketide.ui.screens.project.withRun
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
        val found = mapOf(8000 to Reach.WIFI, 3000 to Reach.PHONE_ONLY, 5173 to Reach.PHONE_ONLY)
        assertEquals(
            listOf(
                PreviewPort(5173, fromAgent = true, reach = Reach.PHONE_ONLY),
                PreviewPort(3000, fromAgent = false, reach = Reach.PHONE_ONLY),
                PreviewPort(8000, fromAgent = false, reach = Reach.WIFI),
            ),
            previewPorts(listOf(5173), found, emptySet()),
        )
        // Announced twice, out of range, or not checked yet: listed once, only when valid, reach unknown.
        assertEquals(
            listOf(PreviewPort(9000, true, null), PreviewPort(3000, false, Reach.PHONE_ONLY)),
            previewPorts(listOf(9000, 9000, 0, 70000), mapOf(3000 to Reach.PHONE_ONLY), emptySet()),
        )
        assertEquals(listOf(3000), previewPorts(emptyList(), mapOf(3000 to Reach.PHONE_ONLY, 8080 to Reach.WIFI), setOf(8080)).map { it.port })
        assertEquals(emptyList<PreviewPort>(), previewPorts(listOf(8080), emptyMap(), excluded = setOf(8080)))
        assertEquals(listOf(3000, 3001, 4200, 5000, 5173, 8000, 8080, 8888), COMMON_DEV_PORTS)
    }

    @Test
    fun previewOpensOnlyWhatTheAgentAnnounced() {
        assertEquals(5173, autoOpenPort(previewPorts(listOf(5173, 3000), mapOf(8000 to Reach.PHONE_ONLY), emptySet())))
        assertNull(autoOpenPort(previewPorts(emptyList(), mapOf(8000 to Reach.PHONE_ONLY), emptySet())))
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
        assertFalse(isLargeTranscript(session("a", transcriptBytes = LARGE_TRANSCRIPT_BYTES), flagged = false))
        assertTrue(isLargeTranscript(session("a", transcriptBytes = LARGE_TRANSCRIPT_BYTES + 1), flagged = false))
        // The sessions module reads the live transcript; its flag wins over a stale record.
        assertTrue(isLargeTranscript(session("a", transcriptBytes = 0), flagged = true))
    }

    @Test
    fun putOnMainSummaryAndWhenItMayGoOn() {
        val changes = SessionChanges(
            commits = listOf("Fix login", "Add test", "Tidy"),
            files = listOf(ChangedFile("a.kt", 100, 4), ChangedFile("b.kt", 20, 10), ChangedFile("c.kt", Int.MAX_VALUE, 0)),
        )
        assertEquals("3 commits · 3 files · +${120L + Int.MAX_VALUE} −14", changeTotals(changes))
        assertEquals("0 commits · 0 files · +0 −0", changeTotals(SessionChanges(emptyList(), emptyList())))
        assertFalse("still reading", canPutOnMain(null))
        assertTrue(canPutOnMain(Result.success(changes)))
        assertFalse("nothing to merge", canPutOnMain(Result.success(SessionChanges(emptyList(), emptyList()))))
        assertTrue("unreadable changes: the check-post still runs", canPutOnMain(Result.failure(IllegalStateException("git"))))
    }

    @Test
    fun theBuildsTabAsksGitHubLessWhileNothingChanges() {
        fun run(id: Long, status: String, runner: String? = null) = WorkflowRun(id, "Android", "b", status, null, "", "", "", runner)
        val listed = listOf(run(9, "in_progress", "macos-15"), run(7, "queued", "ubuntu-latest"))

        // The followed run's progress replaces its entry, or joins a list 20 newer runs pushed it off.
        assertEquals(listOf(run(9, "in_progress", "macos-15"), run(7, "completed")), withRun(listed, run(7, "completed")))
        assertEquals(listOf(5L, 9L, 7L), withRun(listed, run(5, "queued")).map { it.id })

        // A refresh looks no runners up, and keeps the ones the list already showed.
        val refreshed = keepRunners(listed, listOf(run(9, "completed"), run(7, "in_progress"), run(3, "queued")))
        assertEquals(listOf("macos-15", "ubuntu-latest", null), refreshed.map { it.runnerImage })
        assertEquals(listOf(run(4, "queued", "windows-latest")), keepRunners(null, listOf(run(4, "queued", "windows-latest"))))

        assertEquals(listOf(15_000L, 15_000L, 30_000L, 30_000L, 60_000L, 60_000L), (0..5).map(::pollDelayMs))
    }

    @Test
    fun buildsFollowTheirOwnRun() {
        fun run(id: Long, status: String, conclusion: String? = null) = WorkflowRun(id, "Android", "b", status, conclusion, "", "", "")
        val earlier = run(1, "completed", "success")
        assertFalse(needsPolling(listOf(earlier), followed = null))
        assertTrue("an unfinished run", needsPolling(listOf(earlier, run(2, "queued")), followed = null))
        // The run started here is followed by its id, so the list never waits for it, listed or not.
        assertFalse("ours is not listed", needsPolling(listOf(earlier), followed = 7))
        assertFalse("ours is followed on its own", needsPolling(listOf(earlier, run(7, "in_progress")), followed = 7))
        assertTrue("another run is unfinished", needsPolling(listOf(run(8, "queued"), run(7, "in_progress")), followed = 7))
        assertFalse(needsPolling(listOf(earlier, run(7, "completed", "failure")), followed = 7))

        val newer = run(9, "in_progress")
        val mine = run(7, "queued")
        assertEquals(listOf(7L, 9L, 1L), followedFirst(listOf(newer, mine, earlier), 7).map { it.id })
        assertEquals(listOf(9L, 1L), followedFirst(listOf(newer, earlier), 7).map { it.id })
        assertEquals(listOf(9L, 1L), followedFirst(listOf(newer, earlier), null).map { it.id })

        // A red run is reported red, once, and only for the run this phone started.
        val done = run(7, "completed", "failure")
        assertEquals(done, finishedRun(listOf(mine), listOf(done), 7))
        assertEquals("Failed" to Tone.ERROR, runStatus(finishedRun(listOf(mine), listOf(done), 7)!!))
        assertEquals(done, finishedRun(emptyList(), listOf(done), 7))
        assertNull("already reported", finishedRun(listOf(done), listOf(done), 7))
        assertNull("another run finished", finishedRun(listOf(newer), listOf(run(9, "completed", "success")), 7))
        assertNull("first load", finishedRun(null, listOf(done), 7))
        assertNull(finishedRun(listOf(mine), null, 7))
    }

    @Test
    fun trustComesFromTheOwner() {
        assertEquals(Trust.YOURS, trustOf("Me", "me"))
        assertEquals(Trust.SOMEONE_ELSES, trustOf("torvalds", "me"))
        assertEquals("unknown account: no false alarm", Trust.YOURS, trustOf("torvalds", null))
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
