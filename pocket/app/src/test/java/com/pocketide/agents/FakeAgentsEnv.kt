package com.pocketide.agents

import com.pocketide.core.AppDirs
import com.pocketide.core.AppJson
import com.pocketide.model.AgentCandidate
import com.pocketide.model.Decision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * The agents module's world in a temporary folder. Programs "run" in a pretend room: its
 * code-server installs a .vsix by unpacking its package.json into the room's extensions folder
 * (and marks the version it replaces obsolete, as VS Code does), lists what is installed, and
 * uninstalls; agy says its version when its file starts with "good".
 */
internal class FakeAgentsEnv(base: File) : AgentsEnv {
    override val dirs = AppDirs(File(base, "files"), File(base, "cache"))
    var now = 1_800_000_000_000L
    override val clock = com.pocketide.core.Clock { now }
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    override val http: OkHttpClient = client
    override val downloads: OkHttpClient = client
    override val onlyOfficial = MutableStateFlow(false)

    var computer: String? = null
    var vscode: SemVer? = SemVer(1, 138, 0)
    var allowed = Decision.YES
    var memory: String? = null
    val inUse = mutableSetOf<String>()
    val configured = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    /** What [saveBeforeRemoving] reports as still only on the phone. */
    var unsaved = emptyList<String>()
    val savedFirst = mutableListOf<String>()
    val announced = mutableListOf<List<AgentCandidate>>()
    val commands = mutableListOf<List<String>>()
    var recorded = 0L

    override fun computerProblem() = computer
    override fun vscodeVersion() = vscode
    override fun roomInUse(agentId: String) = agentId in inUse
    override suspend fun configureRoom(agentId: String) {
        configured += agentId
    }

    override suspend fun saveBeforeRemoving(agentId: String): List<String> {
        savedFirst += agentId
        return unsaved
    }

    override suspend fun deleteRoom(agentId: String) {
        deleted += agentId
        File(dirs.rooms, agentId).deleteRecursively()
    }

    override fun allowDownload(bytes: Long) = allowed
    override fun recordDownload(bytes: Long) {
        recorded += bytes
    }

    override fun memoryProblem() = memory
    override fun announce(candidates: List<AgentCandidate>) {
        announced += candidates
    }

    override suspend fun runInRoom(agentId: String, argv: List<String>, onLine: (String) -> Unit): Int {
        commands += argv
        val home = dirs.roomHome(agentId)
        return when (argv.first()) {
            RoomPaths.CODE_SERVER -> codeServer(agentId, home, argv.drop(1), onLine)
            RoomPaths.GUEST_AGY -> {
                val binary = File(home, RoomPaths.AGY_IN_HOME)
                if (binary.isFile && binary.readText().startsWith("good")) {
                    onLine("agy 1.2.10")
                    0
                } else {
                    1
                }
            }
            else -> 127
        }
    }

    fun extensionsFolder(agentId: String) = File(dirs.roomHome(agentId), RoomPaths.EXTENSIONS_IN_HOME)

    private fun codeServer(agentId: String, home: File, args: List<String>, onLine: (String) -> Unit): Int {
        val folder = File(home, RoomPaths.EXTENSIONS_IN_HOME).apply { mkdirs() }
        val obsoleteFile = File(folder, ".obsolete")
        val obsolete = if (obsoleteFile.isFile) (AppJson.parseToJsonElement(obsoleteFile.readText()) as JsonObject).keys.toMutableSet() else mutableSetOf()
        fun saveObsolete() = obsoleteFile.writeText(buildJsonObject { obsolete.forEach { put(it, true) } }.toString())
        fun installedFolders() = folder.listFiles().orEmpty().filter { it.isDirectory && it.name !in obsolete }
        fun idOf(dir: File): Pair<String, String> {
            val json = AppJson.parseToJsonElement(File(dir, "package.json").readText()) as JsonObject
            val id = "${json["publisher"]!!.jsonPrimitive.content}.${json["name"]!!.jsonPrimitive.content}"
            return id to json["version"]!!.jsonPrimitive.content
        }
        when {
            "--install-extension" in args -> {
                val guest = args[args.indexOf("--install-extension") + 1]
                val vsix = File(dirs.roomTmp(agentId), guest.removePrefix("/tmp/"))
                if (!vsix.isFile) return 1
                val packageJson = ZipFile(vsix).use { zip -> zip.getInputStream(zip.getEntry("extension/package.json")).readBytes() }
                val json = AppJson.parseToJsonElement(String(packageJson)) as JsonObject
                val id = "${json["publisher"]!!.jsonPrimitive.content}.${json["name"]!!.jsonPrimitive.content}".lowercase()
                val version = json["version"]!!.jsonPrimitive.content
                val name = "$id-$version-linux-arm64"
                installedFolders().filter { idOf(it).first.lowercase() == id && it.name != name }.forEach { obsolete += it.name }
                obsolete -= name
                File(folder, name).apply { mkdirs() }.resolve("package.json").writeBytes(packageJson)
                saveObsolete()
                onLine("Extension '${vsix.name}' was successfully installed.")
                return 0
            }
            "--list-extensions" in args -> {
                installedFolders().forEach { dir -> idOf(dir).let { (id, version) -> onLine("$id@$version") } }
                return 0
            }
            "--uninstall-extension" in args -> {
                val id = args[args.indexOf("--uninstall-extension") + 1].lowercase()
                installedFolders().filter { idOf(it).first.lowercase() == id }.forEach { it.deleteRecursively() }
                return 0
            }
        }
        return 2
    }
}
