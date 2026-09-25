package com.pocketide.agents

import com.pocketide.core.AppJson
import com.pocketide.linux.GuestRoot
import com.pocketide.model.AgentInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** An extension found in a room's extensions folder, with its own package.json. */
internal data class InstalledExtension(val folder: String, val version: String, val packageJson: JsonObject)

/**
 * Tests an agent on this phone: it is installed where its room looks, it fits the computer's
 * code-server, the command that opens it full screen is still there, it starts, and the phone
 * has the memory for it. An update that fails any of these is rolled back.
 */
internal class AgentDoctor(private val env: AgentsEnv, private val runTimeoutMs: Long = RUN_TIMEOUT_MS) {

    /** Tests [agent] as installed now; for an extension, [version] is the one that must be in place. */
    suspend fun check(agent: AgentInfo, version: String? = null): DoctorReport {
        env.computerProblem()?.let { return DoctorReport(agent.id, ok = false, checks = listOf(COMPUTER to false), note = it) }
        val extensionId = agent.extensionId
        return if (extensionId == null) agy(agent) else extension(agent, extensionId, version)
    }

    /**
     * The newest version of [extensionId] in the room's extensions folder. code-server keeps a
     * replaced version's folder until its next start and lists it in `.obsolete`: those are skipped.
     */
    suspend fun installed(agentId: String, extensionId: String): InstalledExtension? = withContext(Dispatchers.IO) {
        val home = GuestRoot(env.dirs.roomHome(agentId))
        val folder = home.directory("/${RoomPaths.EXTENSIONS_IN_HOME}") ?: return@withContext null
        val obsolete = home.readText("/${RoomPaths.EXTENSIONS_IN_HOME}/$OBSOLETE")?.let(::jsonKeys).orEmpty()
        val names = folder.toFile().list().orEmpty().filter { RoomPaths.holds(it, extensionId) && it !in obsolete }
        names.mapNotNull { name ->
            val json = home.readText("/${RoomPaths.EXTENSIONS_IN_HOME}/$name/package.json", MAX_PACKAGE_JSON)
                ?.let { runCatching { AppJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                ?: return@mapNotNull null
            val version = json.string("version") ?: return@mapNotNull null
            val id = "${json.string("publisher")}.${json.string("name")}"
            if (!id.equals(extensionId, ignoreCase = true)) return@mapNotNull null
            InstalledExtension(name, version, json)
        }.maxWithOrNull(compareBy(nullsFirst()) { SemVer.parse(it.version) })
    }

    private suspend fun extension(agent: AgentInfo, extensionId: String, version: String?): DoctorReport {
        val checks = mutableListOf<Pair<String, Boolean>>()
        var note: String? = null
        fun check(name: String, passed: Boolean, why: () -> String) {
            checks += name to passed
            if (!passed && note == null) note = why()
        }

        val found = installed(agent.id, extensionId)?.takeIf { version == null || it.version == version }
        check(INSTALLED, found != null) { "${listOfNotNull(agent.displayName, version).joinToString(" ")} is not installed in its room." }
        val vscode = env.vscodeVersion()
        val engine = found?.packageJson?.obj("engines")?.string("vscode")?.let(EngineRange::parse)
        check(FITS, vscode != null && engine?.accepts(vscode) == true) {
            "This version of ${agent.displayName} needs a newer code-server than the computer has."
        }
        val command = found?.packageJson?.let { openCommand(agent, it) }
        check(FULL_SCREEN, command != null) { "This version of ${agent.displayName} no longer has the screen PocketIDE opens." }
        val starts = found != null && listed(agent.id, extensionId, found.version)
        check(STARTS, starts) { "code-server did not load ${agent.displayName}." }
        val memory = env.memoryProblem()
        check(MEMORY, memory == null) { memory.orEmpty() }
        return DoctorReport(agent.id, ok = checks.all { it.second }, checks = checks, note = note, openCommand = command)
    }

    private suspend fun agy(agent: AgentInfo): DoctorReport {
        val checks = mutableListOf<Pair<String, Boolean>>()
        val present = agyPresent(agent.id)
        checks += INSTALLED to present
        val runs = present && agyRuns(agent.id)
        checks += RUNS to runs
        val memory = env.memoryProblem()
        checks += MEMORY to (memory == null)
        val note = when {
            !present -> "${agent.displayName} is not installed in its room."
            !runs -> "${agent.displayName}'s program did not start on this phone."
            else -> memory
        }
        return DoctorReport(agent.id, ok = checks.all { it.second }, checks = checks, note = note)
    }

    /**
     * The command that opens this version of the agent, chosen from what its package.json
     * declares: the pinned ones for an official agent, the one it was added with, then the
     * view the version contributes. Null when it has none of them.
     */
    private fun openCommand(agent: AgentInfo, packageJson: JsonObject): String? =
        (OfficialAgents.openCommands(agent.id) + listOfNotNull(agent.openCommand) + AgentScreens.openCommands(packageJson))
            .distinct()
            .firstOrNull { AgentScreens.commandExists(packageJson, it) }

    /** True when code-server, started in the room, lists the extension at this version. */
    private suspend fun listed(agentId: String, extensionId: String, version: String): Boolean {
        val wanted = "$extensionId@$version".lowercase()
        var seen = false
        val code = run(agentId, RoomPaths.codeServer("--list-extensions", "--show-versions")) { line ->
            if (line.trim().lowercase() == wanted) seen = true
        }
        return code == 0 && seen
    }

    private suspend fun agyPresent(agentId: String): Boolean = withContext(Dispatchers.IO) {
        val binary = GuestRoot(env.dirs.roomHome(agentId)).existing("/${RoomPaths.AGY_IN_HOME}") ?: return@withContext false
        Files.isRegularFile(binary, LinkOption.NOFOLLOW_LINKS) && Files.size(binary) > 0
    }

    /** agy says its version and exits 0. A kernel whose address space it cannot use makes it abort here. */
    private suspend fun agyRuns(agentId: String): Boolean {
        var version = false
        val code = run(agentId, listOf(RoomPaths.GUEST_AGY, "--version")) { line ->
            if (VERSION.containsMatchIn(line)) version = true
        }
        return code == 0 && version
    }

    /** The exit code, or null when the program could not run or took too long. */
    private suspend fun run(agentId: String, argv: List<String>, onLine: (String) -> Unit): Int? = try {
        withTimeout(runTimeoutMs) { env.runInRoom(agentId, argv, onLine) }
    } catch (tooSlow: TimeoutCancellationException) {
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: IOException) {
        null
    } catch (refused: IllegalStateException) {
        null
    }

    private fun jsonKeys(text: String): Set<String> =
        runCatching { (AppJson.parseToJsonElement(text) as? JsonObject)?.keys }.getOrNull().orEmpty()

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    private fun JsonObject.string(key: String) = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    companion object {
        const val COMPUTER = "The computer is ready"
        const val INSTALLED = "Installed in its room"
        const val FITS = "Made for this computer's code-server"
        const val FULL_SCREEN = "Opens full screen"
        const val STARTS = "Starts in code-server"
        const val RUNS = "Runs on this phone"
        const val MEMORY = "Enough memory on this phone"

        /** code-server's cold start on a slow phone takes most of a minute. */
        private const val RUN_TIMEOUT_MS = 180_000L
        private const val OBSOLETE = ".obsolete"
        private const val MAX_PACKAGE_JSON = 8L * 1024 * 1024
        private val VERSION = Regex("""\d+\.\d+""")
    }
}
