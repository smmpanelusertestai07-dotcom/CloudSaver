package com.pocketide.schedule

import androidx.work.NetworkType
import com.pocketide.core.AppDirs
import com.pocketide.core.Clock
import com.pocketide.model.SessionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class HeadlessCommandTest {
    @Test
    fun eachOfficialAgentHasItsOwnCommandLineMode() {
        for (agent in listOf("claude", "codex", "antigravity")) {
            val argv = HeadlessCommand.argv(agent, "update the tests", "/work/alice__demo/s1")!!
            assertEquals(listOf("/bin/sh", "-c"), argv.take(2))
            assertEquals(listOf("pocketide-task", agent, "update the tests", "/work/alice__demo/s1"), argv.drop(3))
        }
        val script = HeadlessCommand.SCRIPT
        assertTrue(script.contains("-p \"\$prompt\" --output-format text"))
        assertTrue(script.contains("exec --skip-git-repo-check"))
        assertTrue(script.contains("--print-timeout 60m"))
        assertFalse("never --bare: it ignores the subscription login", script.contains("--bare"))
        assertNull(HeadlessCommand.argv("kilocode.kilo-code", "x", "/work"))
    }

    @Test
    fun thePromptIsAnArgumentNeverCode() {
        val argv = HeadlessCommand.argv("claude", "--dangerously-skip-permissions; \$(rm -rf /)", "/work/p/s")!!
        assertFalse(argv[2].contains("rm -rf"))
        assertEquals(" --dangerously-skip-permissions; \$(rm -rf /)", argv[5])
    }

    @Test
    fun noTokenIsEverPassed() {
        for (agent in HeadlessCommand.supported) {
            val env = HeadlessCommand.env(agent)
            assertTrue(env.keys.none { it.contains("TOKEN") || it.contains("KEY") || it.contains("AUTH") })
        }
        assertFalse(HeadlessCommand.SCRIPT.contains("API_KEY"))
    }

    @Test
    fun agyNeedsItsJsonStatusToSucceed() {
        assertTrue(HeadlessCommand.succeeded("claude", 0, emptyList()))
        assertFalse(HeadlessCommand.succeeded("codex", 1, emptyList()))
        assertTrue(HeadlessCommand.succeeded("antigravity", 0, listOf("""{"conversation_id":"c","status":"SUCCESS","response":"done"}""")))
        assertFalse(HeadlessCommand.succeeded("antigravity", 0, listOf("""{"status": "WAITING","response":""}""")))
        assertFalse("a soft-denied permission exits 0", HeadlessCommand.succeeded("antigravity", 0, listOf("permission denied: write_file")))
    }
}

class ScheduleConstraintsTest {
    @Test
    fun runsOnlyWhileChargingOnWifiWithBatteryNotLow() {
        val c = ScheduleWork.constraints()
        assertTrue(c.requiresCharging())
        assertTrue(c.requiresBatteryNotLow())
        assertEquals(NetworkType.UNMETERED, c.requiredNetworkType)
    }

    @Test
    fun eachTaskIsAPeriodicJobOfItsOwnInterval() {
        val request = ScheduleWork.periodic(task(everyHours = 24))
        assertEquals(TimeUnit.HOURS.toMillis(24), request.workSpec.intervalDuration)
        assertTrue(request.workSpec.constraints.requiresCharging())
        assertEquals(NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
        assertEquals("t1", request.workSpec.input.getString(ScheduleWork.KEY_TASK))
        try {
            ScheduleWork.periodic(task(everyHours = 0))
            fail("less than an hour must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("once an hour"))
        }
    }

    @Test
    fun runNowWaitsForTheSameConditions() {
        val request = ScheduleWork.once("t1", "s9")
        assertTrue(request.workSpec.constraints.requiresCharging())
        assertEquals("s9", request.workSpec.input.getString(ScheduleWork.KEY_SESSION))
    }
}

internal fun task(everyHours: Int = 24, agentId: String = "claude") =
    ScheduledTask(id = "t1", projectId = "alice/demo", agentId = agentId, title = "Nightly tests", prompt = "run the tests", everyHours = everyHours)

class ScheduledRunTest {
    @get:Rule val temp = TemporaryFolder()

    /** Built at run time, so no literal in the sources looks like a real token. */
    private val fakeToken = "ghp_" + "a".repeat(36)

    private lateinit var ports: FakeRunPorts

    @Before
    fun setUp() {
        ports = FakeRunPorts(AppDirs(temp.newFolder("files"), temp.newFolder("cache")))
    }

    @Test
    fun aRunMakesASessionSavesTheOutputAndTellsTheOwner() = runTest {
        ports.lines = listOf("Ran 12 tests", "token=$fakeToken")
        val outcome = ScheduledRun(ports).run(task())

        assertTrue(outcome.succeeded)
        assertEquals("s-new", outcome.sessionId)
        // The room decides the binds, the environment and the launcher, as for its other programs.
        val run = ports.runs.single()
        assertEquals("claude", run.agentId)
        assertEquals("alice/demo", run.projectId)
        assertEquals("/work/alice__demo/s-new", run.workDir)
        assertEquals(HeadlessCommand.argv("claude", "run the tests", "/work/alice__demo/s-new"), run.argv)
        assertEquals(HeadlessCommand.env("claude"), run.programEnv)

        val saved = ports.saved.single()
        assertEquals("s-new", saved.first)
        assertTrue(saved.second.contains("Ran 12 tests"))
        assertFalse("tokens are hidden in saved output", saved.second.contains(fakeToken))
        assertEquals(listOf("t1" to "s-new"), ports.recorded)
        assertEquals("Scheduled task finished", ports.notices.single().first)
        assertEquals(listOf("s-new"), ports.afterRuns)
    }

    @Test
    fun aRunStopsAtTheTimeLimitAndKeepsWhatItHas() = runTest {
        ports.lines = listOf("working…")
        ports.hang = true
        val outcome = ScheduledRun(ports, timeLimitMs = 1_000).run(task())

        assertTrue(outcome.timedOut)
        assertFalse(outcome.succeeded)
        assertTrue(ports.saved.single().second.contains("Stopped after 60 minutes"))
        assertTrue(ports.saved.single().second.contains("working…"))
        assertEquals("Scheduled task needs a look", ports.notices.single().first)
        assertEquals(1, ports.recorded.size)
    }

    @Test
    fun heavyWorkRulesAreAskedFirst() = runTest {
        ports.refusal = "The battery is low."
        try {
            ScheduledRun(ports).run(task())
            fail("the limiter must be asked")
        } catch (expected: ScheduleException) {
            assertEquals("The battery is low.", expected.message)
            assertTrue(ports.runs.isEmpty())
            assertNull(ports.started)
        }
    }

    @Test
    fun runNowUsesTheSessionItShowedOrNone() = runTest {
        val outcome = ScheduledRun(ports).run(task(), existingSessionId = "s-shown")
        assertEquals("s-shown", outcome.sessionId)

        ports.known = emptySet()
        try {
            ScheduledRun(ports).run(task(), existingSessionId = "s-gone")
            fail("a missing session is not replaced by a new one")
        } catch (expected: ScheduleException) {
            assertEquals(ScheduledRun.SESSION_GONE, expected.message)
            assertNull(ports.started)
            assertEquals(1, ports.runs.size)
            assertEquals("Scheduled task needs a look", ports.notices.last().first)
        }
    }

    @Test
    fun anAgentWithoutAHeadlessModeIsRefused() = runTest {
        try {
            ScheduledRun(ports).run(task(agentId = "kilocode.kilo-code"))
            fail("no command-line mode")
        } catch (expected: ScheduleException) {
            assertTrue(expected.message!!.contains("no command-line mode"))
        }
    }

    internal data class RoomRun(val agentId: String, val projectId: String, val argv: List<String>, val workDir: String, val programEnv: Map<String, String>)

    internal class FakeRunPorts(private val dirs: AppDirs) : RunPorts {
        override val clock = Clock { 1_000L }
        var lines = emptyList<String>()
        var hang = false
        var refusal: String? = null
        var started: String? = null
        val runs = mutableListOf<RoomRun>()
        val saved = mutableListOf<Pair<String, String>>()
        val recorded = mutableListOf<Pair<String, String>>()
        val notices = mutableListOf<Pair<String, String>>()
        val afterRuns = mutableListOf<String>()

        private fun record(id: String) = SessionRecord(
            id = id, agentId = "claude", projectId = "alice/demo", title = "t", branch = "pocket/claude/x",
            startedAt = 0, lastActivityAt = 0, deviceId = "p",
        )

        override suspend fun startSession(projectId: String, agentId: String, title: String): SessionRecord {
            started = title
            return record("s-new")
        }

        /** Sessions this phone knows; null knows every id. */
        var known: Set<String>? = null
        override suspend fun session(sessionId: String) = record(sessionId).takeIf { known?.contains(sessionId) ?: true }
        override suspend fun runInRoom(
            agentId: String,
            projectId: String,
            argv: List<String>,
            workDir: String,
            programEnv: Map<String, String>,
            onLine: (String) -> Unit,
        ): Int {
            runs += RoomRun(agentId, projectId, argv, workDir, programEnv)
            lines.forEach(onLine)
            if (hang) awaitCancellation()
            return 0
        }
        override fun heavyWorkRefusal() = refusal
        override suspend fun saveOutput(sessionId: String, file: File) {
            saved += sessionId to file.readText()
        }
        override suspend fun afterRun(sessionId: String) {
            afterRuns += sessionId
        }
        override fun scratchFile() = File(dirs.downloads, "task.txt")
        override fun notify(taskId: String, heading: String, text: String) {
            notices += heading to text
        }
        override suspend fun recordRun(taskId: String, at: Long, sessionId: String) {
            recorded += taskId to sessionId
        }
    }
}

class TaskSchedulesTest {
    @get:Rule val temp = TemporaryFolder()

    private class FakeScheduler : TaskScheduler {
        val scheduled = mutableListOf<ScheduledTask>()
        val cancelled = mutableListOf<String>()
        val once = mutableListOf<Pair<String, String>>()
        override fun schedule(task: ScheduledTask, update: Boolean) {
            scheduled += task
        }
        override fun cancel(taskId: String) {
            cancelled += taskId
        }
        override fun runOnce(taskId: String, sessionId: String) {
            once += taskId to sessionId
        }
        var cancelledAll = 0
        override fun cancelAll() {
            cancelledAll++
        }
    }

    private val scheduler = FakeScheduler()
    private var whyNot: String? = null

    private fun schedules(file: File = File(temp.root, "schedules.json")) = TaskSchedules(
        file = file,
        scheduler = scheduler,
        powerAndWifi = { whyNot },
        io = Dispatchers.Unconfined,
        runner = ScheduledRun(ScheduledRunTest.FakeRunPorts(AppDirs(temp.root, temp.root))),
    )

    @Test
    fun tasksAreKeptAndScheduled() = runTest {
        val s = schedules()
        s.save(task())
        assertEquals(listOf("t1"), s.tasks.value.map { it.id })
        assertEquals(1, scheduler.scheduled.size)

        val again = schedules()
        again.load()
        assertEquals("Nightly tests", again.tasks.value.single().title)

        again.remove("t1")
        assertTrue(again.tasks.value.isEmpty())
        assertEquals(listOf("t1"), scheduler.cancelled)
    }

    @Test
    fun badTasksAreRefused() = runTest {
        val s = schedules()
        for (bad in listOf(task(everyHours = 0), task().copy(prompt = " "), task().copy(title = ""), task(agentId = "kilocode.kilo-code"))) {
            try {
                s.save(bad)
                fail("$bad must be refused")
            } catch (expected: ScheduleException) {
                assertNotNull(expected.message)
            }
        }
        assertTrue(s.tasks.value.isEmpty())
    }

    @Test
    fun runNowOnlyWhileChargingOnWifi() = runTest {
        val s = schedules()
        s.save(task())
        whyNot = "The phone is not charging."
        try {
            s.runNow("t1")
            fail("must wait for the charger")
        } catch (expected: ScheduleException) {
            assertEquals("The phone is not charging.", expected.message)
            assertTrue(scheduler.once.isEmpty())
        }
        whyNot = null
        assertEquals("s-new", s.runNow("t1"))
        assertEquals(listOf("t1" to "s-new"), scheduler.once)
    }

    @Test
    fun noOldTaskRunsOrComesBackAfterDeleteEverything() = runTest {
        val file = File(temp.root, "schedules.json")
        val s = schedules(file)
        s.save(task())
        s.save(task().copy(id = "t2", title = "Weekly clean-up"))
        // Delete everything removes the file first, then the module forgets what it holds.
        file.delete()
        s.forgetEverything()
        assertEquals("every task's job leaves WorkManager", 1, scheduler.cancelledAll)
        assertTrue(s.tasks.value.isEmpty())

        // A run that ends afterwards, and a task saved afterwards, never write the old ones back.
        s.recordRun("t1", 9_000, "s-9")
        s.save(task().copy(id = "t3", title = "New"))
        val reread = schedules(file).apply { load() }
        assertEquals(listOf("t3"), reread.tasks.value.map { it.id })
    }

    @Test
    fun runsAreRecordedAndSurviveEdits() = runTest {
        val s = schedules()
        s.save(task())
        s.recordRun("t1", 5_000, "s-7")
        s.save(task().copy(title = "Renamed"))
        val saved = s.tasks.value.single()
        assertEquals("Renamed", saved.title)
        assertEquals(5_000L, saved.lastRunAt)
        assertEquals("s-7", saved.lastSessionId)
    }
}
