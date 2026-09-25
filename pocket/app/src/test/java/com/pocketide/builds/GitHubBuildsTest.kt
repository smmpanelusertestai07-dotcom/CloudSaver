package com.pocketide.builds

import android.net.Uri
import com.pocketide.core.Clock
import com.pocketide.git.Hold
import com.pocketide.git.HoldKind
import com.pocketide.github.AccountUsage
import com.pocketide.github.DispatchedRun
import com.pocketide.github.GitHubAccount
import com.pocketide.github.GitHubApi
import com.pocketide.github.JobLog
import com.pocketide.github.JobStep
import com.pocketide.github.PullRequest
import com.pocketide.github.RepoFile
import com.pocketide.github.RepoInfo
import com.pocketide.github.RepoUsage
import com.pocketide.github.RunArtifact
import com.pocketide.github.WorkflowJob
import com.pocketide.github.WorkflowRun
import com.pocketide.media.MediaItem
import com.pocketide.media.MediaKind
import com.pocketide.media.MediaLibrary
import com.pocketide.media.MediaSniffer
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

class SafeUnzipTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun unpacksNestedFiles() {
        val dest = temp.newFolder("out")
        val files = SafeUnzip().unzip(ByteArrayInputStream(zipOf("out/screen.png" to byteArrayOf(1), "log.txt" to "ok".toByteArray())), dest)
        assertEquals(listOf("screen.png", "log.txt"), files.map { it.name })
        assertTrue(File(dest, "out/screen.png").isFile)
    }

    @Test
    fun rejectsEntriesThatClimbOut() {
        for (evil in listOf("../evil.sh", "out/../../evil.sh", "/etc/passwd", "..\\evil.bat", "C:/evil.exe")) {
            val dest = temp.newFolder()
            try {
                SafeUnzip().unzip(ByteArrayInputStream(zipOf(evil to "x".toByteArray())), dest)
                fail("$evil must be rejected")
            } catch (expected: UnsafeZipException) {
                assertFalse(File(dest.parentFile, "evil.sh").exists())
            }
        }
    }

    @Test
    fun countsTheBytesReallyWritten() {
        val dest = temp.newFolder()
        try {
            SafeUnzip(maxEntryBytes = 1000).unzip(ByteArrayInputStream(zipOf("big.bin" to ByteArray(5000))), dest)
            fail("an entry over the cap must be rejected")
        } catch (expected: UnsafeZipException) {
            assertTrue(expected.message!!.contains("larger"))
        }
        try {
            SafeUnzip(maxTotalBytes = 1500).unzip(ByteArrayInputStream(zipOf("a" to ByteArray(1000), "b" to ByteArray(1000))), temp.newFolder())
            fail("results over the total cap must be rejected")
        } catch (expected: UnsafeZipException) {
            assertTrue(expected.message!!.contains("unpacked"))
        }
        try {
            SafeUnzip(maxEntries = 2).unzip(ByteArrayInputStream(zipOf("a" to byteArrayOf(), "b" to byteArrayOf(), "c" to byteArrayOf())), temp.newFolder())
            fail("too many entries must be rejected")
        } catch (expected: UnsafeZipException) {
            assertTrue(expected.message!!.contains("more than 2"))
        }
    }
}

class GitHubBuildsTest {
    @get:Rule val temp = TemporaryFolder()

    private val project = Project(id = "alice/demo", owner = "alice", repo = "demo", addedAt = 0, lastActivityAt = 0, cloned = true)
    private val session = SessionRecord(
        id = "s1", agentId = "claude", projectId = "alice/demo", title = "Fix login", branch = "pocket/claude/2026-09-24-fix-login",
        startedAt = 0, lastActivityAt = 0, deviceId = "phone",
    )
    private lateinit var worktree: File
    private lateinit var ports: FakePorts

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2)
    private val apk = "PK\u0003\u0004rest".toByteArray(Charsets.ISO_8859_1)

    @Before
    fun setUp() {
        worktree = temp.newFolder("worktree")
        File(worktree, ".git").writeText("gitdir: /repos/alice__demo.git/worktrees/s1")
        ports = FakePorts(worktree, temp.newFolder("scratch"))
    }

    @Test
    fun addTemplateWritesTheWorkflowAndCommitsOnlyIt() = runTest {
        GitHubBuilds(ports).addTemplate("alice/demo", "s1", TemplateCatalog.ANDROID_RELEASE)
        val written = File(worktree, ".github/workflows/pocketide-android-release.yml")
        assertEquals("template android-release", written.readText())
        assertEquals(listOf(".github/workflows/pocketide-android-release.yml", ".github/dependabot.yml"), ports.committed)
    }

    @Test
    fun theFirstTemplateBringsDependabotForItsPinnedActions() = runTest {
        val builds = GitHubBuilds(ports)
        builds.addTemplate("alice/demo", "s1", TemplateCatalog.ANDROID_RELEASE)
        val dependabot = File(worktree, ".github/dependabot.yml").readLines()
        assertTrue(dependabot.contains("version: 2"))
        assertTrue(dependabot.contains("  - package-ecosystem: github-actions"))
        assertTrue(dependabot.contains("    directory: /"))

        ports.committed.clear()
        builds.addTemplate("alice/demo", "s1", TemplateCatalog.DOCKER)
        assertEquals(listOf(".github/workflows/pocketide-docker-build.yml"), ports.committed)
    }

    @Test
    fun theOwnersOwnDependabotFileIsLeftAlone() = runTest {
        File(worktree, ".github").mkdirs()
        val own = File(worktree, ".github/dependabot.yaml").apply { writeText("version: 2\nupdates: []\n") }
        GitHubBuilds(ports).addTemplate("alice/demo", "s1", TemplateCatalog.ANDROID_RELEASE)
        assertEquals("version: 2\nupdates: []\n", own.readText())
        assertFalse(File(worktree, ".github/dependabot.yml").exists())
        assertEquals(listOf(".github/workflows/pocketide-android-release.yml"), ports.committed)
    }

    @Test
    fun aDependabotLinkIsNeitherFollowedNorReplaced() = runTest {
        val outside = temp.newFile("elsewhere.yml")
        File(worktree, ".github").mkdirs()
        val link = File(worktree, ".github/dependabot.yml").toPath()
        Files.createSymbolicLink(link, outside.toPath())
        GitHubBuilds(ports).addTemplate("alice/demo", "s1", TemplateCatalog.ANDROID_RELEASE)
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("", outside.readText())
        assertEquals(listOf(".github/workflows/pocketide-android-release.yml"), ports.committed)
    }

    @Test
    fun addTemplateNeverWritesThroughALink() = runTest {
        val outside = temp.newFolder("room-home")
        Files.createSymbolicLink(File(worktree, ".github").toPath(), outside.toPath())
        try {
            GitHubBuilds(ports).addTemplate("alice/demo", "s1", TemplateCatalog.ANDROID_RELEASE)
            fail("a linked .github must be refused")
        } catch (expected: BuildsException) {
            assertTrue(outside.list()!!.isEmpty())
            assertTrue(ports.committed.isEmpty())
        }
    }

    @Test
    fun runNeedsTheTemplateAndASavedBranch() = runTest {
        val builds = GitHubBuilds(ports)
        try {
            builds.run("alice/demo", TemplateCatalog.ANDROID_RELEASE, session.branch)
            fail("the template must be added first")
        } catch (expected: BuildsException) {
            assertTrue(expected.message!!.contains("Add the"))
        }
        builds.addTemplate("alice/demo", "s1", TemplateCatalog.ANDROID_RELEASE)
        ports.autosaveReason = "The check-post held the push: a Secret's value is in app/config.txt."
        try {
            builds.run("alice/demo", TemplateCatalog.ANDROID_RELEASE, session.branch)
            fail("an unsaved branch must not be built")
        } catch (expected: BuildsException) {
            assertEquals(ports.autosaveReason, expected.message)
            assertTrue(ports.gitHub.dispatched.isEmpty())
        }
    }

    @Test
    fun runFollowsTheRunThisDispatchStartedNotTheLatest() = runTest {
        val builds = GitHubBuilds(ports)
        builds.addTemplate("alice/demo", "s1", TemplateCatalog.ANDROID_RELEASE)
        ports.now = Instant.parse("2026-09-24T10:00:00Z").toEpochMilli()
        ports.gitHub.runList = listOf(
            run(41, "PocketIDE Android release", "2026-09-24T09:00:00Z"),
            run(43, "PocketIDE iOS simulator", "2026-09-24T10:00:02Z"),
            run(42, "PocketIDE Android release", "2026-09-24T10:00:03Z"),
        )
        val id = builds.run("alice/demo", TemplateCatalog.ANDROID_RELEASE, session.branch)
        assertEquals(42L, id)
        assertEquals(listOf("pocketide-android-release.yml@${session.branch}"), ports.gitHub.dispatched)
        assertEquals(listOf(42L), ports.followed)
    }

    @Test
    fun runFollowsTheIdTheDispatchReturned() = runTest {
        val builds = GitHubBuilds(ports)
        builds.addTemplate("alice/demo", "s1", TemplateCatalog.ANDROID_RELEASE)
        ports.gitHub.dispatchedRunId = 77
        // A newer run of the same workflow must not be taken for it.
        ports.gitHub.runList = listOf(run(78, "PocketIDE Android release", "2026-09-24T10:00:05Z"))
        assertEquals(77L, builds.run("alice/demo", TemplateCatalog.ANDROID_RELEASE, session.branch))
        assertEquals(listOf(77L), ports.followed)
        assertEquals(0, ports.gitHub.runListings)
    }

    @Test
    fun anUnapprovedWorkflowChangeNeverRuns() = runTest {
        val builds = GitHubBuilds(ports)
        builds.addTemplate("alice/demo", "s1", TemplateCatalog.ANDROID_RELEASE)
        val hold = Hold(HoldKind.WORKFLOW_CHANGE, ".github/workflows/pocketide-android-release.yml", "abc1234", "Changes it.", "key", "diff")
        ports.holds = listOf(hold)
        try {
            builds.run("alice/demo", TemplateCatalog.ANDROID_RELEASE, session.branch)
            fail("a held workflow change must be approved first")
        } catch (expected: WorkflowApprovalNeeded) {
            assertEquals(listOf(hold), expected.holds)
            assertTrue(expected.message!!.contains(hold.path))
        }
        assertEquals(0, ports.autosaves)
        assertTrue(ports.gitHub.dispatched.isEmpty())
    }

    @Test
    fun anAgentCannotStartABuildThatUsesTheProjectsSecrets() = runTest {
        val builds = GitHubBuilds(ports)
        builds.addTemplate("alice/demo", "s1", TemplateCatalog.ANDROID_RELEASE)
        try {
            builds.runForAgent("alice/demo", TemplateCatalog.ANDROID_RELEASE, session.branch)
            fail("only the owner starts a build that signs with the project's key")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("only the owner starts it"))
        }
        assertEquals(0, ports.autosaves)
        assertTrue(ports.gitHub.dispatched.isEmpty())

        // The owner's tap starts it, and an agent may start a build without Secrets.
        builds.run("alice/demo", TemplateCatalog.ANDROID_RELEASE, session.branch)
        builds.addTemplate("alice/demo", "s1", TemplateCatalog.DOCKER)
        builds.runForAgent("alice/demo", TemplateCatalog.DOCKER, session.branch)
        assertEquals(
            listOf("pocketide-android-release.yml@${session.branch}", "pocketide-docker-build.yml@${session.branch}"),
            ports.gitHub.dispatched,
        )
    }

    @Test
    fun progressShowsStepsAndWhyARunFailed() = runTest {
        val builds = GitHubBuilds(ports)
        ports.gitHub.runList = listOf(run(5, "PocketIDE Android release", "2026-09-24T10:00:00Z"))
        val steps = listOf(JobStep(1, "Set up job", "completed", "success"), JobStep(2, "Build", "in_progress", null))
        ports.gitHub.jobList = listOf(WorkflowJob(50, "build", "in_progress", null, "", listOf("ubuntu-latest"), "GitHub Actions 2", steps))
        val live = builds.progress("alice/demo", 5)!!
        assertEquals(steps, live.jobs.single().steps)
        assertNull(live.failureLog)
        assertEquals(0, ports.gitHub.logReads)

        ports.gitHub.runList = listOf(run(5, "PocketIDE Android release", "2026-09-24T10:00:00Z").copy(status = "completed", conclusion = "failure"))
        ports.gitHub.jobList = listOf(
            WorkflowJob(49, "lint", "completed", "success", "", emptyList(), null, emptyList()),
            WorkflowJob(50, "build", "completed", "failure", "", emptyList(), null, listOf(steps[0], JobStep(2, "Build", "completed", "failure"))),
        )
        val tail = (1..40).joinToString("\n") { "2026-09-24T10:0${it % 10}:00.1234567Z line $it" }
        ports.gitHub.logs[50] = JobLog("ubuntu-24.04 20260920.1", tail)
        val ended = builds.progress("alice/demo", 5)!!
        assertEquals("build", ended.failedJob)
        assertEquals("Build", ended.failedStep)
        assertEquals((11..40).joinToString("\n") { "line $it" }, ended.failureLog)
        assertEquals("ubuntu-24.04 20260920.1", ended.run.runnerImage)
        builds.progress("alice/demo", 5)
        assertEquals("an ended run's log is read once", 1, ports.gitHub.logReads)
        assertNull(builds.progress("alice/demo", 6))
    }

    @Test
    fun collectKeepsTheEndOfAFailedRunsLog() = runTest {
        ports.gitHub.runList = listOf(run(5, "PocketIDE Android release", "2026-09-24T10:00:00Z").copy(status = "completed", conclusion = "failure"))
        ports.gitHub.jobList = listOf(WorkflowJob(50, "build", "completed", "failure", "", emptyList(), null, emptyList()))
        ports.gitHub.logs[50] = JobLog(null, "error: cannot find symbol\nFAILURE: Build failed")
        assertEquals(1, GitHubBuilds(ports).collect("alice/demo", "s1", 5))
        val log = ports.media.added.single()
        assertEquals("run-5-failure-log.txt" to MediaKind.TEXT, log.name to log.kind)
        assertTrue("scratch is cleaned", ports.scratchRoot.list()!!.isEmpty())
    }

    @Test
    fun runReturnsNullWhenGitHubDoesNotListItYet() = runTest {
        val builds = GitHubBuilds(ports)
        builds.addTemplate("alice/demo", "s1", TemplateCatalog.ANDROID_RELEASE)
        ports.now = Instant.parse("2026-09-24T10:00:00Z").toEpochMilli()
        ports.gitHub.runList = listOf(run(41, "PocketIDE Android release", "2026-09-24T09:00:00Z"))
        assertNull(builds.run("alice/demo", TemplateCatalog.ANDROID_RELEASE, session.branch))
        assertTrue(ports.followed.isEmpty())
    }

    @Test
    fun collectBringsPicturesApksAndReportsIntoMedia() = runTest {
        ports.gitHub.artifactList = listOf(RunArtifact(7, "pocketide-emulator-api-35", 100, false, "u"))
        ports.gitHub.zips[7] = zipOf(
            "out/screenshot.png" to png,
            "out/app-debug.apk" to apk,
            "out/logcat.txt" to "I/App: started".toByteArray(),
            "app/build/reports/androidTests/connected/index.html" to "<!doctype html><p>ok".toByteArray(),
            "app/build/reports/androidTests/connected/classes/LoginTest.html" to "<!doctype html><p>x".toByteArray(),
            "out/core.bin" to byteArrayOf(0, 1, 2, 3),
        )
        val count = GitHubBuilds(ports).collect("alice/demo", "s1", 99)
        assertEquals(4, count)
        assertEquals(
            listOf(
                "emulator-api-35-out-screenshot.png" to MediaKind.IMAGE,
                "emulator-api-35-out-app-debug.apk" to MediaKind.APK,
                "emulator-api-35-out-logcat.txt" to MediaKind.TEXT,
                "emulator-api-35-app-build-reports-androidTests-connected-index.html" to MediaKind.HTML,
            ),
            ports.media.added.map { it.name to it.kind },
        )
        assertTrue(ports.media.added.all { it.source == "actions" })
        assertTrue("scratch is cleaned", ports.scratchRoot.list()!!.isEmpty())
    }

    @Test
    fun collectRejectsZipSlipAndKeepsNothing() = runTest {
        ports.gitHub.artifactList = listOf(RunArtifact(8, "pocketide-linux-x64", 100, false, "u"))
        ports.gitHub.zips[8] = zipOf("out/ok.png" to png, "../../escape.png" to png)
        try {
            GitHubBuilds(ports).collect("alice/demo", "s1", 99)
            fail("a zip that climbs out must be refused")
        } catch (expected: BuildsException) {
            assertTrue(expected.message!!.contains("outside"))
        }
        assertTrue(ports.media.added.isEmpty())
        assertFalse(File(ports.scratchRoot.parentFile, "escape.png").exists())
    }

    @Test
    fun theSignedApksReplaceTheUnsignedOnes() = runTest {
        ports.gitHub.artifactList = listOf(
            RunArtifact(11, "pocketide-android-release-unsigned", 100, false, "u"),
            RunArtifact(12, "pocketide-android-release-reports", 100, false, "u"),
            RunArtifact(13, "pocketide-android-release", 100, false, "u"),
        )
        ports.gitHub.zips[11] = zipOf("app-release-unsigned.apk" to apk)
        ports.gitHub.zips[12] = zipOf("app/lint/index.html" to "<!doctype html><p>ok".toByteArray())
        ports.gitHub.zips[13] = zipOf("app-release.apk" to apk)

        GitHubBuilds(ports).collect("alice/demo", "s1", 99)

        assertEquals(listOf(12L, 13L), ports.gitHub.downloads)
        assertEquals(listOf("android-release-reports-app-lint-index.html", "android-release-app-release.apk"), ports.media.added.map { it.name })
    }

    @Test
    fun withoutTheSigningSecretsTheUnsignedApksComeBack() = runTest {
        ports.gitHub.artifactList = listOf(RunArtifact(11, "pocketide-android-release-unsigned", 100, false, "u"))
        ports.gitHub.zips[11] = zipOf("app-release-unsigned.apk" to apk)

        GitHubBuilds(ports).collect("alice/demo", "s1", 99)

        assertEquals(listOf("android-release-unsigned-app-release-unsigned.apk"), ports.media.added.map { it.name })
    }

    @Test
    fun expiredResultsAreExplained() = runTest {
        ports.gitHub.artifactList = listOf(RunArtifact(9, "pocketide-macos", 100, true, "u"))
        try {
            GitHubBuilds(ports).collect("alice/demo", "s1", 99)
            fail("expired artifacts cannot be brought")
        } catch (expected: BuildsException) {
            assertTrue(expected.message!!.contains("no longer on GitHub"))
        }
    }

    @Test
    fun mobileDataRulesAreAskedFirst() = runTest {
        ports.gitHub.artifactList = listOf(RunArtifact(10, "pocketide-android-release", 90_000_000, false, "u"))
        ports.refusal = "Big downloads wait for Wi-Fi."
        try {
            GitHubBuilds(ports).collect("alice/demo", "s1", 99)
            fail("the data rules must be asked")
        } catch (expected: BuildsException) {
            assertEquals("Big downloads wait for Wi-Fi.", expected.message)
            assertTrue(ports.gitHub.downloads.isEmpty())
        }
    }

    @Test
    fun templatesAreSuggestedByProjectType() {
        val flutter = temp.newFolder("flutter").apply { File(this, "pubspec.yaml").writeText("name: app") }
        assertEquals(listOf(TemplateCatalog.FLUTTER_ANDROID, TemplateCatalog.IOS_SIMULATOR), TemplateCatalog.suggestedFor(flutter).map { it.id })

        val rn = temp.newFolder("rn").apply {
            File(this, "package.json").writeText("""{"dependencies":{"react-native":"0.80.0"}}""")
            File(this, "ios").mkdir()
            File(this, "ios/Podfile").writeText("")
        }
        assertEquals(
            listOf(TemplateCatalog.REACT_NATIVE_ANDROID, TemplateCatalog.IOS_SIMULATOR, TemplateCatalog.MACOS),
            TemplateCatalog.suggestedFor(rn).map { it.id },
        )

        val gradle = temp.newFolder("gradle").apply {
            File(this, "settings.gradle.kts").writeText("")
            File(this, "Dockerfile").writeText("FROM scratch")
        }
        assertEquals(
            listOf(TemplateCatalog.ANDROID_RELEASE, TemplateCatalog.ANDROID_EMULATOR, TemplateCatalog.DOCKER),
            TemplateCatalog.suggestedFor(gradle).map { it.id },
        )
        assertEquals(TemplateCatalog.all, TemplateCatalog.suggestedFor(temp.newFolder("empty")))
    }

    private fun run(id: Long, name: String, created: String) =
        WorkflowRun(id, name, session.branch, "queued", null, created, created, "https://github.com/alice/demo/actions/runs/$id")

    private inner class FakePorts(private val tree: File, val scratchRoot: File) : BuildsPorts {
        override val gitHub = FakeGitHub()
        override val media = FakeMedia()
        override val clock = Clock { now }
        override val io = Dispatchers.Unconfined
        var now = 0L
        var autosaveReason: String? = null
        var autosaves = 0
        var holds = emptyList<Hold>()
        var refusal: String? = null
        val committed = mutableListOf<String>()
        val followed = mutableListOf<Long>()

        override fun project(projectId: String) = project.takeIf { it.id == projectId }
        override fun sessions() = listOf(session)
        override fun worktree(session: SessionRecord) = tree
        override fun scratch() = File(scratchRoot, "run")
        override fun templateBytes(template: BuildTemplate) = "template ${template.id}".toByteArray()
        override suspend fun commit(session: SessionRecord, path: String, message: String) {
            committed += path
        }
        override suspend fun autosave(sessionId: String): String? {
            autosaves++
            return autosaveReason
        }
        override suspend fun workflowHolds(projectId: String, branch: String) = holds
        override fun downloadRefusal(bytes: Long) = refusal
        override fun downloaded(bytes: Long) = Unit
        override fun follow(projectId: String, runId: Long, title: String) {
            followed += runId
        }
    }

    private class FakeMedia : MediaLibrary {
        val added = mutableListOf<MediaItem>()
        override fun forSession(sessionId: String): Flow<List<MediaItem>> = flowOf(added)
        override suspend fun add(sessionId: String, source: File, name: String, from: String): MediaItem {
            val kind = kindOf(name, MediaSniffer.head(source))
            return MediaItem(sessionId, source, name, kind, source.length(), 0, true, false, from).also { added += it }
        }
        override fun kindOf(name: String, head: ByteArray) = MediaSniffer.kindOf(name, head)
        override suspend fun delete(item: MediaItem) = Unit
        override fun shareUri(item: MediaItem): Uri = Uri.EMPTY
    }

    private class FakeGitHub : GitHubApi {
        var runList = emptyList<WorkflowRun>()
        var artifactList = emptyList<RunArtifact>()
        var jobList = emptyList<WorkflowJob>()
        val logs = mutableMapOf<Long, JobLog>()
        var dispatchedRunId: Long? = null
        var runListings = 0
        var logReads = 0
        val zips = mutableMapOf<Long, ByteArray>()
        val dispatched = mutableListOf<String>()
        val downloads = mutableListOf<Long>()

        override suspend fun dispatchWorkflow(owner: String, name: String, workflowFile: String, ref: String, inputs: Map<String, String>) {
            dispatched += "$workflowFile@$ref"
        }
        override suspend fun dispatchWorkflowRun(owner: String, name: String, workflowFile: String, ref: String, inputs: Map<String, String>): DispatchedRun? {
            dispatchWorkflow(owner, name, workflowFile, ref, inputs)
            return dispatchedRunId?.let { DispatchedRun(it, "", "") }
        }
        override suspend fun runs(owner: String, name: String, branch: String?): List<WorkflowRun> {
            runListings++
            return runList.filter { branch == null || it.branch == branch }
        }
        override suspend fun run(owner: String, name: String, runId: Long) = runList.find { it.id == runId }
        override suspend fun jobs(owner: String, name: String, runId: Long) = jobList
        override suspend fun jobLog(owner: String, name: String, jobId: Long): JobLog? {
            logReads++
            return logs[jobId]
        }
        override suspend fun artifacts(owner: String, name: String, runId: Long) = artifactList
        override suspend fun downloadArtifact(artifact: RunArtifact, dest: File) {
            downloads += artifact.id
            dest.writeBytes(zips.getValue(artifact.id))
        }

        private fun no(): Nothing = throw UnsupportedOperationException()
        override suspend fun me(): GitHubAccount = no()
        override suspend fun repos(): List<RepoInfo> = no()
        override suspend fun repo(owner: String, name: String): RepoInfo? = no()
        override suspend fun createPrivateRepo(name: String, description: String, autoInit: Boolean): RepoInfo = no()
        override suspend fun collaborators(owner: String, name: String): List<String> = no()
        override suspend fun setActionsEnabled(owner: String, name: String, enabled: Boolean) = no()
        override suspend fun readFile(owner: String, name: String, path: String): RepoFile? = no()
        override suspend fun writeFile(owner: String, name: String, path: String, bytes: ByteArray, message: String, sha: String?) = no()
        override suspend fun openPullRequest(owner: String, name: String, head: String, base: String, title: String, body: String): PullRequest = no()
        override suspend fun pullRequest(owner: String, name: String, number: Int): PullRequest = no()
        override suspend fun mergePullRequest(owner: String, name: String, number: Int, method: String): Boolean = no()
        override suspend fun setActionsSecret(owner: String, name: String, secretName: String, value: ByteArray) = no()
        override suspend fun accountUsage(): AccountUsage = no()
        override suspend fun repoUsage(owner: String, name: String): RepoUsage = no()
    }
}
