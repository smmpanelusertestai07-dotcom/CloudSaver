package com.pocketide.rooms

import com.pocketide.builds.BuildProgress
import com.pocketide.builds.BuildTemplate
import com.pocketide.builds.WorkflowApprovalNeeded
import com.pocketide.core.AgentFiles
import com.pocketide.core.AppDirs
import com.pocketide.github.PullRequest
import com.pocketide.media.MediaItem
import com.pocketide.model.Guard
import com.pocketide.model.PhoneSnapshot
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.model.Thermal
import com.pocketide.sessions.PutOnMainResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.util.Locale

/** What the MCP tools need from the rest of the app. */
internal interface McpPorts {
    fun sessions(agentId: String): List<SessionRecord>

    /** The session the agent's room shows now, if any. */
    fun currentSession(agentId: String): String?

    fun project(projectId: String): Project?
    fun phone(): PhoneSnapshot
    fun guard(): Guard
    fun maxAgents(): Int

    /** True while PocketIDE is on the screen, so the owner sees what the agent does. */
    fun ownerPresent(): Boolean
    suspend fun autosave(sessionId: String): String?
    suspend fun putOnMain(sessionId: String): PutOnMainResult
    fun templates(): List<BuildTemplate>
    suspend fun runBuild(projectId: String, templateId: String, ref: String): Long?
    /** The run [runId] of this project by its id, however old; null when GitHub has no such run. */
    suspend fun progress(projectId: String, runId: Long): BuildProgress?
    suspend fun collect(projectId: String, sessionId: String, runId: Long): Int
    suspend fun openPullRequest(project: Project, head: String, title: String, body: String): PullRequest
    suspend fun addMedia(sessionId: String, file: File, name: String): MediaItem
    fun announcePort(sessionId: String, port: Int)

    /** Ports PocketIDE itself uses (engines, terminals, the bridge): never a dev server. */
    fun ownPorts(): Set<Int>

    /** Starts (or reports on) the test browser install; returns what to tell the agent. */
    suspend fun browser(agentId: String): String

    /** Lines of /proc/net/tcp and tcp6, or empty when Android does not let the app read them. */
    fun listeners(): List<String>

    /** The agent started build [runId] and waits for it: its room is busy until the run ends. */
    fun buildStarted(agentId: String, runId: Long) = Unit

    /** The agent saw build [runId] end. */
    fun buildEnded(agentId: String, runId: Long) = Unit
}

/**
 * The app's side of PocketIDE's MCP tools (`mcp.py` forwards each call over the room's phone
 * socket). A failure is thrown as IllegalArgumentException or IllegalStateException with a
 * sentence for the agent; the result is {"text": ...}.
 */
internal class McpTools(private val dirs: AppDirs, private val ports: McpPorts) {

    suspend fun call(agentId: String, request: JsonObject): JsonElement {
        val tool = request.string("tool") ?: throw IllegalArgumentException("No tool was named.")
        val input = request["args"] as? JsonObject ?: JsonObject(emptyMap())
        val cwd = request.string("cwd")
        val text = when (tool) {
            "phone_status" -> phoneStatus()
            "install_browser" -> ports.browser(agentId)
            "run_build" -> runBuild(agentId, session(agentId, cwd), input)
            "build_result" -> buildResult(agentId, session(agentId, cwd), input)
            "open_pr" -> openPr(session(agentId, cwd), input)
            "put_on_main" -> putOnMain(session(agentId, cwd))
            "save_media" -> saveMedia(agentId, session(agentId, cwd), input)
            "preview_port" -> previewPort(session(agentId, cwd), input)
            else -> throw IllegalArgumentException("PocketIDE has no tool called $tool.")
        }
        return buildJsonObject { put("text", text) }
    }

    /**
     * The session a call belongs to: the worktree the agent works in (`/work/<project>/<session>`),
     * else the session the room shows now. Only this agent's own sessions count.
     */
    fun session(agentId: String, cwd: String?): SessionRecord {
        val own = ports.sessions(agentId).filter { it.status != SessionStatus.DELETED }
        val parts = cwd?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
        val fromCwd = if (parts.size >= 3 && "/" + parts[0] == AppDirs.GUEST_WORK) {
            own.firstOrNull { it.id == parts[2] && AppDirs.projectDirName(it.projectId) == parts[1] }
        } else {
            null
        }
        return fromCwd
            ?: ports.currentSession(agentId)?.let { current -> own.firstOrNull { it.id == current } }
            ?: throw IllegalStateException("This chat is not in a PocketIDE session. Open it from a project in PocketIDE.")
    }

    private fun phoneStatus(): String {
        val phone = ports.phone()
        val allowed = when (ports.guard()) {
            Guard.OK -> "Everything is allowed now."
            Guard.NO_NEW_HEAVY -> "No new heavy work on the phone now (battery or heat): send builds and big test runs to GitHub Actions with run_build."
            Guard.NO_NEW_AGENTS -> "Memory is short: keep work small, and send builds and big test runs to GitHub Actions with run_build."
            Guard.PAUSE -> "The phone needs a rest (battery or heat): finish the current step and avoid heavy work."
            Guard.SAFE_STOP -> "The phone is about to stop work safely (battery or heat): save and commit now."
        }
        if (phone.at == 0L) return "Phone readings are not available yet. $allowed"
        return listOf(
            "Battery ${phone.batteryPercent} %" + if (phone.charging) " (charging)." else ".",
            "Heat: ${heat(phone.thermal)}.",
            "Free memory ${gb(phone.availRamBytes)} of ${gb(phone.totalRamBytes)}; this app and Linux use ${gb(phone.appPssBytes)}.",
            "Free storage ${gb(phone.storageFreeBytes)}.",
            when {
                !phone.online -> "Offline."
                phone.metered -> "On mobile data: big downloads wait for Wi-Fi."
                else -> "On Wi-Fi."
            },
            "Processes: ${phone.processCount} (Android may stop apps that run more than 32).",
            "Agents that may run at once: ${ports.maxAgents()}.",
            allowed,
        ).joinToString(" ")
    }

    private suspend fun runBuild(agentId: String, session: SessionRecord, input: JsonObject): String {
        val templateId = input.string("template")?.trim().orEmpty()
        val templates = ports.templates()
        check(templates.isNotEmpty()) { "This project has no build templates yet. Tell the owner to add one from the project's Builds panel." }
        val template = templates.firstOrNull { it.id == templateId }
            ?: throw IllegalArgumentException("Unknown template \"$templateId\". This project has: " + templates.joinToString("; ") { "${it.id} (${it.title})" } + ".")
        pushFirst(session)
        val runId = try {
            ports.runBuild(session.projectId, template.id, session.branch)
        } catch (held: WorkflowApprovalNeeded) {
            // Only the owner approves, on the diff card in PocketIDE; never the agent.
            val paths = held.holds.map { it.path }.distinct().joinToString(", ")
            throw IllegalStateException(
                "The owner must read and approve the change to GitHub Actions code in $paths in PocketIDE before this build can run.",
            )
        } ?: throw IllegalStateException("GitHub did not start the build. The workflow file may not be on this branch yet: commit it, then try again.")
        ports.buildStarted(agentId, runId)
        return "Build started on GitHub Actions: ${template.title}, run $runId on ${session.branch}. " +
            "Call build_result with run_id $runId in a few minutes."
    }

    private suspend fun buildResult(agentId: String, session: SessionRecord, input: JsonObject): String {
        val runId = (input["run_id"] as? JsonPrimitive)?.longOrNull ?: throw IllegalArgumentException("run_id must be a number.")
        val progress = ports.progress(session.projectId, runId)
            ?: throw IllegalArgumentException("Run $runId is not one of this project's runs on GitHub.")
        val run = progress.run
        if (run.status != "completed") {
            return listOfNotNull(
                "Run $runId is still ${run.status.replace('_', ' ')}.",
                steps(progress),
                "Check again in a few minutes. ${run.htmlUrl}",
            ).joinToString(" ")
        }
        ports.buildEnded(agentId, runId)
        val saved = ports.collect(session.projectId, session.id, runId)
        val outcome = when (run.conclusion) {
            "success" -> "succeeded"
            null -> "finished"
            else -> "finished: ${run.conclusion.replace('_', ' ')}"
        }
        val files = if (saved == 1) "1 file was" else "$saved files were"
        return listOfNotNull(
            "Run $runId $outcome.",
            failure(progress),
            "$files saved to this session's Media. Logs: ${run.htmlUrl}",
        ).joinToString(" ")
    }

    /** Where a running build is: each job's current step, or null before GitHub lists any. */
    private fun steps(progress: BuildProgress): String? {
        val lines = progress.jobs.map { job ->
            val step = job.steps.firstOrNull { it.status == "in_progress" } ?: job.steps.lastOrNull { it.status == "completed" }
            if (step == null) "${job.name}: ${job.status.replace('_', ' ')}" else "${job.name}: step ${step.number} \"${step.name}\""
        }
        return if (lines.isEmpty()) null else "Now: ${lines.joinToString("; ")}."
    }

    /** For a failed run: the job and step that failed and the last lines of its log, for the agent to fix. */
    private fun failure(progress: BuildProgress): String? {
        val job = progress.failedJob ?: return null
        val where = "It failed in job \"$job\"" + (progress.failedStep?.let { ", step \"$it\"" } ?: "") + "."
        val tail = progress.failureLog?.lines()?.takeLast(FAILURE_LINES)?.joinToString("\n")?.takeIf { it.isNotBlank() }
            ?: return where
        return "$where The last lines of its log:\n$tail\n"
    }

    private suspend fun openPr(session: SessionRecord, input: JsonObject): String {
        val title = input.string("title")?.trim()?.take(MAX_TITLE).orEmpty()
        require(title.isNotEmpty()) { "A pull request needs a title." }
        val body = input.string("body")?.take(MAX_BODY).orEmpty()
        val project = ports.project(session.projectId) ?: throw IllegalStateException("This session's project is not on this phone.")
        pushFirst(session)
        val pr = ports.openPullRequest(project, session.branch, title, body)
        return "Pull request #${pr.number} is open: ${pr.url}"
    }

    /** Only while the owner has PocketIDE on the screen: main is never changed behind their back. */
    private suspend fun putOnMain(session: SessionRecord): String {
        check(ports.ownerPresent()) { "Put on main runs only while the owner has PocketIDE open. Ask them to open it and to ask you again." }
        return putOnMainNow(session)
    }

    private suspend fun putOnMainNow(session: SessionRecord): String = when (val result = ports.putOnMain(session.id)) {
        PutOnMainResult.Merged -> "Done: this session's work is on the default branch and pushed."
        is PutOnMainResult.Conflicts -> "Merge conflicts in: ${result.files.take(MAX_LISTED).joinToString(", ")}. " +
            "Resolve them in this session's worktree, commit, then call put_on_main again."
        is PutOnMainResult.Blocked -> throw IllegalStateException("Not put on main: ${result.why}")
        is PutOnMainResult.Failed -> throw IllegalStateException("Not put on main: ${result.why}")
    }

    private suspend fun pushFirst(session: SessionRecord) {
        ports.autosave(session.id)?.let { reason -> throw IllegalStateException("This session's branch could not be pushed first: $reason") }
    }

    private suspend fun saveMedia(agentId: String, session: SessionRecord, input: JsonObject): String {
        val guestPath = input.string("path")?.trim().orEmpty()
        require(guestPath.startsWith("/")) { "Give the file's full path." }
        val path = RoomLayout.hostPath(dirs, agentId, guestPath)
            ?: throw IllegalArgumentException("Only files in this session's worktree, /tmp or your home can be saved.")
        val secret = AgentFiles.isSecret(guestPath.trimStart('/')) ||
            RoomLayout.homeRelative(guestPath)?.let(AgentFiles::isSecret) == true
        require(!secret) { "That looks like a sign-in or key file; it is never saved to Media." }
        val copy = withContext(Dispatchers.IO) { copyOut(path, guestPath) }
        try {
            val item = ports.addMedia(session.id, copy, mediaName(input.string("title"), path.file.name))
            return "Saved to this session's Media as \"${item.name}\". The owner sees it there whenever this chat is reopened."
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { copy.delete() }
        }
    }

    /**
     * A copy, in the app's own scratch folder, of the file the room named: opened once from the
     * room's folder without following a link anywhere on the way, so Media gets exactly that
     * file even if the room swaps a link in right after (a link could point anywhere in the
     * app's storage, or into another room).
     */
    private fun copyOut(path: RoomPath, guestPath: String): File {
        val channel = RoomFiles(path.root, guardSecrets = false).open(path.relative)
            ?: throw IllegalArgumentException("$guestPath is not a file (or it is a link).")
        return channel.use { source ->
            require(source.size() <= MAX_MEDIA_BYTES) { TOO_BIG }
            val folder = File(dirs.downloads, STAGING).apply { mkdirs() }
            val copy = File.createTempFile("room-", ".part", folder)
            var copied = false
            try {
                copy.outputStream().use { out -> copyCapped(Channels.newInputStream(source), out) }
                copied = true
            } finally {
                if (!copied) copy.delete()
            }
            copy
        }
    }

    /** Copies at most [MAX_MEDIA_BYTES]; a file that grew past that while copied is refused. */
    private fun copyCapped(input: InputStream, out: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return
            total += n
            require(total <= MAX_MEDIA_BYTES) { TOO_BIG }
            out.write(buffer, 0, n)
        }
    }

    private fun previewPort(session: SessionRecord, input: JsonObject): String {
        val port = (input["port"] as? JsonPrimitive)?.longOrNull?.toInt() ?: throw IllegalArgumentException("port must be a number.")
        require(port in 1024..65535) { "Use a port from 1024 to 65535." }
        require(port !in ports.ownPorts()) { "Port $port is one of PocketIDE's own. Pick another for the dev server." }
        check(Loopback.isListening(port)) { "Nothing is listening on port $port yet. Start the dev server first, bound to 127.0.0.1." }
        ports.announcePort(session.id, port)
        val open = ListenerTable.openToNetwork(ports.listeners(), port)
        val warning = if (open) {
            " Warning: it listens on every network address, so anyone on the same Wi-Fi can open it. Restart it bound to 127.0.0.1."
        } else {
            ""
        }
        return "Port $port is in Preview now; the owner can open it from the Preview tab.$warning"
    }

    private fun heat(thermal: Thermal) = when (thermal) {
        Thermal.NONE -> "normal"
        Thermal.LIGHT -> "warm"
        Thermal.MODERATE -> "hot"
        else -> "very hot"
    }

    private fun gb(bytes: Long) = String.format(Locale.US, "%.1f GB", bytes / 1e9)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    companion object {
        private const val MAX_TITLE = 256
        private const val MAX_BODY = 60_000
        private const val MAX_LISTED = 20
        private const val FAILURE_LINES = 30
        const val MAX_MEDIA_BYTES = 200_000_000L
        private const val TOO_BIG = "That file is too big for Media (over ${MAX_MEDIA_BYTES / 1_000_000} MB)."
        private const val STAGING = "room-media"
        private const val COPY_BUFFER = 64 * 1024
        private val UNSAFE_NAME = Regex("[^A-Za-z0-9 ._()-]")

        /** Tools that push or write for the owner while they run: the room must not close under them. */
        private val WRITING_TOOLS = setOf("run_build", "open_pr", "put_on_main", "save_media")

        fun writes(request: JsonObject): Boolean = (request["tool"] as? JsonPrimitive)?.contentOrNull in WRITING_TOOLS

        /** A plain file name for Media: the title when given (keeping the file's extension), else the file's name. */
        fun mediaName(title: String?, fileName: String): String {
            val extension = fileName.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 8 }
            val base = title?.trim()?.takeIf { it.isNotEmpty() } ?: fileName.substringBeforeLast('.')
            val clean = UNSAFE_NAME.replace(base, "_").trim('.', ' ').take(80).ifEmpty { "file" }
            return if (extension == null || clean.endsWith(".$extension", ignoreCase = true)) clean else "$clean.$extension"
        }
    }
}

/** Reads /proc/net/tcp and tcp6 lines: which address a listening port is bound to. */
internal object ListenerTable {
    private const val LISTEN = "0A"

    /** True when [port] listens on every address (0.0.0.0 or ::), not only on loopback. */
    fun openToNetwork(lines: List<String>, port: Int): Boolean = lines.any { line ->
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 4 || fields[3] != LISTEN) return@any false
        val local = fields[1]
        val address = local.substringBefore(':')
        val localPort = local.substringAfter(':', "").toIntOrNull(16)
        localPort == port && address.all { it == '0' }
    }
}
