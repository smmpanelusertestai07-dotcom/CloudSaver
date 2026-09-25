package com.pocketide.builds

import com.pocketide.core.Clock
import com.pocketide.core.Redact
import com.pocketide.git.Hold
import com.pocketide.github.GitHubApi
import com.pocketide.github.GitHubException
import com.pocketide.github.RunArtifact
import com.pocketide.github.WorkflowJob
import com.pocketide.github.WorkflowRun
import com.pocketide.github.runnerLabels
import com.pocketide.media.MediaException
import com.pocketide.media.MediaKind
import com.pocketide.media.MediaLibrary
import com.pocketide.media.MediaSniffer
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/** Something the owner can act on, in one plain sentence. */
open class BuildsException(message: String) : Exception(message)

/**
 * The branch changes GitHub Actions code the owner has not approved, so the build does not start.
 * Each hold carries the diff to show and the key [com.pocketide.git.GitGate.approveWorkflowChange]
 * takes once the owner approved it; then the build can be started again.
 */
class WorkflowApprovalNeeded(val holds: List<Hold>) : BuildsException(
    "Read and approve the change to GitHub Actions code in ${holds.joinToString(", ") { it.path }} before this build runs.",
)

/** What builds use from other modules and from Android, so the logic can be tested on its own. */
internal interface BuildsPorts {
    val gitHub: GitHubApi
    val media: MediaLibrary
    val clock: Clock
    val io: CoroutineDispatcher

    fun project(projectId: String): Project?
    fun sessions(): List<SessionRecord>

    /** The session's worktree on the phone (host path). */
    fun worktree(session: SessionRecord): File

    /** A scratch folder for downloads; it is deleted after use. */
    fun scratch(): File

    /** The template's text, from the app's assets. */
    fun templateBytes(template: BuildTemplate): ByteArray

    /** Commits one file of the session's worktree inside Linux, as the owner. */
    suspend fun commit(session: SessionRecord, path: String, message: String)

    /** Pushes the session's branch through the check-post; a plain reason when it could not. */
    suspend fun autosave(sessionId: String): String?

    /** The check-post's holds on [branch] for workflow changes the owner has not approved yet. */
    suspend fun workflowHolds(projectId: String, branch: String): List<Hold>

    /** Whether [bytes] may be downloaded now (mobile data rules); a plain reason when not. */
    fun downloadRefusal(bytes: Long): String?

    fun downloaded(bytes: Long)

    /** Watches the run and posts a notification when it ends. */
    fun follow(projectId: String, runId: Long, title: String)
}

/**
 * Builds on the owner's GitHub Actions: templates committed to a session's branch, runs started
 * by workflow_dispatch on that branch, and results brought back into the session's Media.
 */
internal class GitHubBuilds(
    private val ports: BuildsPorts,
    private val unzip: SafeUnzip = SafeUnzip(),
) : Builds {

    override fun templates(): List<BuildTemplate> = TemplateCatalog.all

    override suspend fun suggestedTemplates(projectId: String, sessionId: String): List<BuildTemplate> {
        val session = sessionOf(projectId, sessionId)
        return withContext(ports.io) { TemplateCatalog.suggestedFor(ports.worktree(session)) }
    }

    override suspend fun addTemplate(projectId: String, sessionId: String, templateId: String) {
        val template = templateOf(templateId)
        val session = sessionOf(projectId, sessionId)
        val worktree = ports.worktree(session)
        val path = TemplateCatalog.repoPath(template)
        val addDependabot = withContext(ports.io) {
            if (!File(worktree, ".git").exists()) throw BuildsException("This session's files are not on this phone. Open the session first.")
            writeInside(worktree, path, ports.templateBytes(template))
            // The owner's own Dependabot file, or a link in its place, is left as it is.
            val absent = TemplateCatalog.dependabotPaths.none { Files.exists(File(worktree, it).toPath(), LinkOption.NOFOLLOW_LINKS) }
            if (absent) writeInside(worktree, TemplateCatalog.DEPENDABOT_PATH, TemplateCatalog.dependabot.toByteArray())
            absent
        }
        ports.commit(session, path, "Add the ${template.title} build (PocketIDE)")
        if (addDependabot) ports.commit(session, TemplateCatalog.DEPENDABOT_PATH, "Keep the build actions up to date with Dependabot (PocketIDE)")
    }

    override suspend fun run(projectId: String, templateId: String, ref: String): Long? {
        val template = templateOf(templateId)
        val project = projectOf(projectId)
        val session = ports.sessions().firstOrNull { it.projectId == projectId && it.branch == ref }
        if (session != null) {
            val added = withContext(ports.io) { File(ports.worktree(session), TemplateCatalog.repoPath(template)).isFile }
            if (!added) throw BuildsException("Add the ${template.title} template to this session first.")
            // The run uses what GitHub has, and only the check-post puts it there (A4).
            val holds = ports.workflowHolds(projectId, ref)
            if (holds.isNotEmpty()) throw WorkflowApprovalNeeded(holds)
            ports.autosave(session.id)?.let { throw BuildsException(it) }
        }
        val since = ports.clock.now()
        val runId = ports.gitHub.dispatchWorkflowRun(project.owner, project.repo, template.fileName, ref)?.runId
            ?: findRun(project, ref, template, since)
        if (runId != null) ports.follow(projectId, runId, template.title)
        return runId
    }

    override suspend fun recentRuns(projectId: String): List<WorkflowRun> = recentRuns(projectId, runners = true)

    override suspend fun recentRuns(projectId: String, runners: Boolean): List<WorkflowRun> {
        val project = projectOf(projectId)
        return ports.gitHub.runs(project.owner, project.repo, null, runners).take(RECENT_RUNS)
    }

    /** Two requests: the run and its jobs, which also name the runner it asked for. */
    override suspend fun progress(projectId: String, runId: Long): BuildProgress? {
        val project = projectOf(projectId)
        val listed = ports.gitHub.run(project.owner, project.repo, runId) ?: return null
        val jobs = ports.gitHub.jobs(project.owner, project.repo, runId)
        val run = listed.copy(runnerImage = listed.runnerImage ?: runnerLabels(jobs))
        if (run.status != COMPLETED) return BuildProgress(run, jobs)
        val ending = ending(project, run, jobs)
        return BuildProgress(
            run = run.copy(runnerImage = ending.runnerImage ?: run.runnerImage),
            jobs = jobs,
            failedJob = ending.failedJob?.name,
            failedStep = ending.failedJob?.steps?.firstOrNull { it.conclusion in FAILED }?.name,
            failureLog = ending.failureLog,
        )
    }

    override suspend fun collect(projectId: String, sessionId: String, runId: Long): Int {
        val project = projectOf(projectId)
        sessionOf(projectId, sessionId)
        val logs = keepFailureLog(projectId, sessionId, runId)
        val artifacts = ports.gitHub.artifacts(project.owner, project.repo, runId)
        if (artifacts.isEmpty()) return logs
        val live = artifacts.filterNot { it.expired }.let(::withoutReplaced)
        if (live.isEmpty()) throw BuildsException("These results are no longer on GitHub. Run the build again.")
        ports.downloadRefusal(live.sumOf { it.sizeBytes })?.let { throw BuildsException(it) }
        val scratch = ports.scratch()
        return try {
            val added = LinkedHashSet<String>()
            for (artifact in live) {
                if (added.size >= MAX_ITEMS_PER_RUN) break
                added += bring(artifact, sessionId, File(scratch, artifact.id.toString()), MAX_ITEMS_PER_RUN - added.size)
            }
            logs + added.size
        } finally {
            withContext(ports.io) { scratch.deleteRecursively() }
        }
    }

    /** Adds the end of a failed run's log to Media, for the owner and the agent; returns 1 when it did. */
    private suspend fun keepFailureLog(projectId: String, sessionId: String, runId: Long): Int {
        val log = try {
            progress(projectId, runId)?.failureLog
        } catch (e: GitHubException) {
            // The run's files still come in without it.
            null
        } ?: return 0
        val folder = ports.scratch()
        return try {
            val file = withContext(ports.io) {
                folder.mkdirs()
                File(folder, "failure.txt").apply { writeText(log) }
            }
            ports.media.add(sessionId, file, "run-$runId-failure-log.txt", FROM_ACTIONS)
            1
        } catch (e: MediaException) {
            0
        } finally {
            withContext(ports.io) { folder.deleteRecursively() }
        }
    }

    /**
     * What an ended run leaves to show: the runner image from a job's log, and for a run that did
     * not succeed its first failed job with the last lines of that job's log. Read once per run.
     */
    private suspend fun ending(project: Project, run: WorkflowRun, jobs: List<WorkflowJob>): Ending {
        synchronized(endings) { endings[run.id] }?.let { return it }
        val failed = if (run.conclusion == SUCCESS) null else jobs.firstOrNull { it.conclusion in FAILED }
        val job = failed ?: jobs.firstOrNull() ?: return Ending(null, null, null)
        val log = try {
            ports.gitHub.jobLog(project.owner, project.repo, job.id)
        } catch (e: GitHubException) {
            // The log is a detail: the run's result stands without it, and the next look tries again.
            return Ending(null, failed, null)
        }
        val ending = Ending(log?.runnerImage, failed, failed?.let { log?.tail?.let(::lastLines) })
        synchronized(endings) { endings[run.id] = ending }
        return ending
    }

    /**
     * A template that signs in a job of its own uploads the unsigned build as `<name>-unsigned`
     * and the signed one as `<name>`; when both are there, only the signed one is brought back.
     */
    private fun withoutReplaced(artifacts: List<RunArtifact>): List<RunArtifact> {
        val names = artifacts.mapTo(HashSet()) { it.name }
        return artifacts.filterNot { it.name.endsWith(UNSIGNED) && it.name.removeSuffix(UNSIGNED) in names }
    }

    /** Downloads one artifact, unpacks it safely and adds what Media shows; returns the stored paths. */
    private suspend fun bring(artifact: RunArtifact, sessionId: String, folder: File, room: Int): List<String> {
        val zip = File(folder.parentFile, "${artifact.id}.zip")
        withContext(ports.io) { folder.parentFile?.mkdirs() }
        try {
            ports.gitHub.downloadArtifact(artifact, zip)
        } catch (e: IOException) {
            throw BuildsException("Could not download ${artifact.name}. Check the connection and try again.")
        }
        val files = withContext(ports.io) {
            ports.downloaded(zip.length())
            try {
                zip.inputStream().use { unzip.unzip(it, folder) }
            } catch (e: UnsafeZipException) {
                throw BuildsException("${artifact.name} was not opened: ${e.message}")
            } finally {
                zip.delete()
            }
        }
        val label = artifact.name.removePrefix("pocketide-")
        val stored = ArrayList<String>()
        for (file in files) {
            if (stored.size >= room) break
            if (!withContext(ports.io) { worthKeeping(file) }) continue
            val relative = file.relativeTo(folder).invariantSeparatorsPath
            val name = "$label-" + relative.replace('/', '-')
            try {
                stored += ports.media.add(sessionId, file, name, FROM_ACTIONS).file.path
            } catch (e: MediaException) {
                // Too big or unreadable: the rest of the results still come in.
            }
        }
        return stored
    }

    /** What the Media strip is for: pictures, videos, APKs, report pages and short logs. */
    private fun worthKeeping(file: File): Boolean {
        return when (ports.media.kindOf(file.name, MediaSniffer.head(file))) {
            MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.APK, MediaKind.PDF -> true
            // A report is a folder of pages; its entry page is enough.
            MediaKind.HTML -> file.name.equals("index.html", ignoreCase = true)
            MediaKind.TEXT -> file.length() <= MAX_LOG_BYTES
            MediaKind.OTHER -> false
        }
    }

    /**
     * The run this dispatch started. GitHub lists it a moment after the request; only a run of
     * this workflow on this branch created after [since] counts, never merely the latest run.
     */
    private suspend fun findRun(project: Project, ref: String, template: BuildTemplate, since: Long): Long? {
        repeat(FIND_ATTEMPTS) {
            delay(FIND_DELAY_MS)
            val match = ports.gitHub.runs(project.owner, project.repo, ref, runners = false)
                .filter { it.name == template.workflowName }
                .mapNotNull { run -> createdAt(run)?.let { run to it } }
                .filter { (_, created) -> created >= since - CLOCK_SKEW_MS }
                .minByOrNull { (_, created) -> created }
            if (match != null) return match.first.id
        }
        return null
    }

    /** The log's last lines without GitHub's timestamps; masked Secrets stay masked, and tokens are taken out. */
    private fun lastLines(tail: String): String? = tail.lineSequence()
        .map { it.replace(TIMESTAMP, "").trimEnd() }
        .filter(String::isNotEmpty)
        .toList()
        .takeLast(LOG_LINES)
        .joinToString("\n")
        .let(Redact::text)
        .takeIf(String::isNotBlank)

    private fun createdAt(run: WorkflowRun): Long? = try {
        Instant.parse(run.createdAt).toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }

    /**
     * Writes [path] inside [root] without following links: an agent can put a link where
     * `.github` should be, and the app must not write through it into its own files.
     */
    private fun writeInside(root: File, path: String, bytes: ByteArray) {
        var dir = root
        for (part in path.split('/').dropLast(1)) {
            dir = File(dir, part)
            val p = dir.toPath()
            if (Files.isSymbolicLink(p)) throw BuildsException("$path cannot be written: ${dir.name} is a link.")
            if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && !dir.mkdir()) throw BuildsException("Could not create ${dir.name} in the session.")
        }
        val target = File(root, path)
        if (Files.isSymbolicLink(target.toPath())) throw BuildsException("$path cannot be written: it is a link.")
        val temp = File(dir, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            temp.writeBytes(bytes)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
        // A folder swapped for a link while writing: take the file back out and stop.
        if (!target.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
            target.delete()
            throw BuildsException("$path cannot be written: a folder on the way is a link.")
        }
    }

    private fun templateOf(templateId: String) =
        TemplateCatalog.find(templateId) ?: throw BuildsException("There is no build template called $templateId.")

    private fun projectOf(projectId: String) =
        ports.project(projectId) ?: throw BuildsException("This project is not on this phone any more.")

    private fun sessionOf(projectId: String, sessionId: String) =
        ports.sessions().firstOrNull { it.id == sessionId && it.projectId == projectId }
            ?: throw BuildsException("This session is not on this phone any more.")

    private class Ending(val runnerImage: String?, val failedJob: WorkflowJob?, val failureLog: String?)

    /** Endings of recent runs; an ended run does not change. */
    private val endings = object : LinkedHashMap<Long, Ending>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Ending>?) = size > KEPT_ENDINGS
    }

    private companion object {
        const val FROM_ACTIONS = "actions"
        const val UNSIGNED = "-unsigned"
        const val COMPLETED = "completed"
        const val SUCCESS = "success"
        val FAILED = setOf("failure", "timed_out", "startup_failure", "cancelled", "action_required")
        const val LOG_LINES = 30
        const val KEPT_ENDINGS = 32
        val TIMESTAMP = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z """)
        const val RECENT_RUNS = 20
        const val FIND_ATTEMPTS = 6
        const val FIND_DELAY_MS = 2_500L
        const val CLOCK_SKEW_MS = 60_000L
        const val MAX_ITEMS_PER_RUN = 60
        const val MAX_LOG_BYTES = 1024L * 1024
    }
}
