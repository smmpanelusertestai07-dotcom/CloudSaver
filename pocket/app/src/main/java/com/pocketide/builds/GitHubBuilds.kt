package com.pocketide.builds

import com.pocketide.core.Clock
import com.pocketide.github.GitHubApi
import com.pocketide.github.RunArtifact
import com.pocketide.github.WorkflowRun
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
class BuildsException(message: String) : Exception(message)

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
        withContext(ports.io) {
            if (!File(worktree, ".git").exists()) throw BuildsException("This session's files are not on this phone. Open the session first.")
            writeInside(worktree, path, ports.templateBytes(template))
        }
        ports.commit(session, path, "Add the ${template.title} build (PocketIDE)")
    }

    override suspend fun run(projectId: String, templateId: String, ref: String): Long? {
        val template = templateOf(templateId)
        val project = projectOf(projectId)
        val session = ports.sessions().firstOrNull { it.projectId == projectId && it.branch == ref }
        if (session != null) {
            val added = withContext(ports.io) { File(ports.worktree(session), TemplateCatalog.repoPath(template)).isFile }
            if (!added) throw BuildsException("Add the ${template.title} template to this session first.")
            ports.autosave(session.id)?.let { throw BuildsException(it) }
        }
        val since = ports.clock.now()
        ports.gitHub.dispatchWorkflow(project.owner, project.repo, template.fileName, ref)
        val runId = findRun(project, ref, template, since)
        if (runId != null) ports.follow(projectId, runId, template.title)
        return runId
    }

    override suspend fun recentRuns(projectId: String): List<WorkflowRun> {
        val project = projectOf(projectId)
        return ports.gitHub.runs(project.owner, project.repo).take(RECENT_RUNS)
    }

    override suspend fun collect(projectId: String, sessionId: String, runId: Long): Int {
        val project = projectOf(projectId)
        sessionOf(projectId, sessionId)
        val artifacts = ports.gitHub.artifacts(project.owner, project.repo, runId)
        if (artifacts.isEmpty()) return 0
        val live = artifacts.filterNot { it.expired }
        if (live.isEmpty()) throw BuildsException("These results are no longer on GitHub. Run the build again.")
        ports.downloadRefusal(live.sumOf { it.sizeBytes })?.let { throw BuildsException(it) }
        val scratch = ports.scratch()
        return try {
            val added = LinkedHashSet<String>()
            for (artifact in live) {
                if (added.size >= MAX_ITEMS_PER_RUN) break
                added += bring(artifact, sessionId, File(scratch, artifact.id.toString()), MAX_ITEMS_PER_RUN - added.size)
            }
            added.size
        } finally {
            withContext(ports.io) { scratch.deleteRecursively() }
        }
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
            if (!worthKeeping(file)) continue
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
            val match = ports.gitHub.runs(project.owner, project.repo, ref)
                .filter { it.name == template.workflowName }
                .mapNotNull { run -> createdAt(run)?.let { run to it } }
                .filter { (_, created) -> created >= since - CLOCK_SKEW_MS }
                .minByOrNull { (_, created) -> created }
            if (match != null) return match.first.id
        }
        return null
    }

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
    }

    private fun templateOf(templateId: String) =
        TemplateCatalog.find(templateId) ?: throw BuildsException("There is no build template called $templateId.")

    private fun projectOf(projectId: String) =
        ports.project(projectId) ?: throw BuildsException("This project is not on this phone any more.")

    private fun sessionOf(projectId: String, sessionId: String) =
        ports.sessions().firstOrNull { it.id == sessionId && it.projectId == projectId }
            ?: throw BuildsException("This session is not on this phone any more.")

    private companion object {
        const val FROM_ACTIONS = "actions"
        const val RECENT_RUNS = 20
        const val FIND_ATTEMPTS = 6
        const val FIND_DELAY_MS = 2_500L
        const val CLOCK_SKEW_MS = 60_000L
        const val MAX_ITEMS_PER_RUN = 60
        const val MAX_LOG_BYTES = 1024L * 1024
    }
}
