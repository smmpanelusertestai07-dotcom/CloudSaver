package com.pocketide.rooms

import com.pocketide.core.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A setting that can run code (a hook, an MCP server, a permission rule, an environment
 * variable) which an agent added in its room ([Rooms.configChanges]). The room's file no longer
 * holds it: every room start writes these settings from PocketIDE's own and those the owner kept.
 */
@Serializable
data class ConfigChange(
    val agentId: String,
    /** The file, under the room's home: ".claude/settings.json". */
    val file: String,
    /** Where in the file: "hooks", "env", "permissions.allow", "mcp_servers"… */
    val place: String,
    /** Its name there (a variable, a server, a hook event), or empty. */
    val key: String,
    /** The setting as the file held it: JSON, or TOML lines. */
    val value: String,
    /** False when PocketIDE cannot write it back as it was written (Codex's servers as one inline table). */
    val keepable: Boolean = true,
) {
    internal val entry: Entry get() = Entry(place, key, value, keepable)

    internal fun sameAs(other: ConfigChange) =
        agentId == other.agentId && file == other.file && place == other.place && key == other.key && value == other.value
}

/** One plain sentence for the owner: what [ConfigChange] lets the agent called [agentName] do. */
fun ConfigChange.sentence(agentName: String): String {
    val named = key.takeIf { it.isNotEmpty() }
    val what = when {
        place == "hooks" || file.endsWith("hooks.json") -> "added a hook" + (named?.let { " for $it" } ?: "") + " that runs a command by itself"
        place == "mcp_servers" || place == "mcpServers" -> "added the tool server ${named ?: "list"}, a program it starts"
        place.endsWith(".allow") -> "allowed itself to do this without asking"
        place.endsWith("defaultMode") || place.endsWith("initialPermissionMode") || place.endsWith("allowDangerouslySkipPermissions") ->
            "changed how much it may do without asking"
        place == "env" || place.contains("environment", ignoreCase = true) || place.startsWith("terminal.integrated.env") ->
            "set the environment variable${named?.let { " $it" }.orEmpty()} for the programs it runs"
        place == "notify" -> "set the program it runs after each answer"
        place == "model_providers" -> "added the model provider ${named.orEmpty()}, which receives your prompts and can run a command".trim()
        place == "enabledPlugins" || place == "extraKnownMarketplaces" -> "turned on a plugin source${named?.let { ": $it" }.orEmpty()}"
        place.startsWith("terminal.integrated") -> "changed the shell its terminal starts"
        else -> "set $place${named?.let { " ($it)" }.orEmpty()}, which starts a program"
    }
    val runs = commandsIn(value).takeIf { it.isNotEmpty() }?.let { " It runs: ${it.joinToString("; ")}." }.orEmpty()
    return "$agentName $what.$runs"
}

/** The commands a JSON setting names ("command" fields, with their "args"); empty for anything else. */
private fun commandsIn(value: String): List<String> {
    val element = try {
        Json.parseToJsonElement(value)
    } catch (unreadable: SerializationException) {
        return emptyList()
    } catch (unreadable: IllegalArgumentException) {
        return emptyList()
    }
    val found = mutableListOf<String>()
    fun walk(node: JsonElement) {
        when (node) {
            is JsonObject -> {
                val command = (node["command"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                if (command != null) {
                    val args = (node["args"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    found += (listOf(command) + args).joinToString(" ")
                }
                node.values.forEach(::walk)
            }
            is JsonArray -> node.forEach(::walk)
            is JsonPrimitive -> Unit
        }
    }
    walk(element)
    return found.take(MAX_COMMANDS).map { it.take(MAX_COMMAND_CHARS) }
}

private const val MAX_COMMANDS = 3
private const val MAX_COMMAND_CHARS = 200

/**
 * The owner's say over settings that can run code, room by room: what agents added, waiting for
 * the owner ([pending]), and what the owner kept ([kept]), which every room start writes back.
 * Stored on the app's side, beside each room's home and never in it: nothing in Linux can add to
 * what is kept. Only a change that is waiting can be kept.
 */
internal class ConfigChangeBook(private val rooms: File) {
    @Serializable
    private data class Stored(val pending: List<ConfigChange> = emptyList(), val kept: List<ConfigChange> = emptyList())

    private val stored = HashMap<String, Stored>()
    private val mutablePending = MutableStateFlow<List<ConfigChange>>(emptyList())
    private val mutableKept = MutableStateFlow<List<ConfigChange>>(emptyList())
    val pending: StateFlow<List<ConfigChange>> = mutablePending.asStateFlow()
    val kept: StateFlow<List<ConfigChange>> = mutableKept.asStateFlow()

    /** What the owner kept in [file] of [agentId]'s room, to write back. */
    @Synchronized
    fun kept(agentId: String, file: String): List<Entry> = of(agentId).kept.filter { it.file == file && it.keepable }.map { it.entry }

    /** Records what an agent added to [file]; each waits for the owner once. Returns the ones new to the list. */
    @Synchronized
    fun found(agentId: String, file: String, added: List<Entry>): List<ConfigChange> {
        val current = of(agentId)
        val fresh = added.filter { it.value.length <= MAX_VALUE_CHARS }
            .map { ConfigChange(agentId, file, it.place, it.key, it.value, it.keepable) }
            .filter { change -> current.pending.none { it.sameAs(change) } }
            .distinctBy { listOf(it.place, it.key, it.value) }
        if (fresh.isNotEmpty()) save(agentId, current.copy(pending = (current.pending + fresh).takeLast(MAX_PENDING)))
        return fresh
    }

    /** Keeps [change], when it is waiting: from now on it is written into its room. */
    @Synchronized
    fun keep(change: ConfigChange): Boolean {
        val current = of(change.agentId)
        val waiting = current.pending.firstOrNull { it.sameAs(change) }?.takeIf { it.keepable } ?: return false
        save(change.agentId, current.copy(pending = current.pending.filterNot { it.sameAs(waiting) }, kept = current.kept + waiting))
        return true
    }

    /** Lets a waiting [change] go: it is already out of its room's file. */
    @Synchronized
    fun drop(change: ConfigChange) {
        val current = of(change.agentId)
        if (current.pending.any { it.sameAs(change) }) save(change.agentId, current.copy(pending = current.pending.filterNot { it.sameAs(change) }))
    }

    /** Stops keeping [change]: the next write of its room leaves it out. */
    @Synchronized
    fun stopKeeping(change: ConfigChange): Boolean {
        val current = of(change.agentId)
        if (current.kept.none { it.sameAs(change) }) return false
        save(change.agentId, current.copy(kept = current.kept.filterNot { it.sameAs(change) }))
        return true
    }

    /** Reads every room's list, for the owner to see before any room starts. */
    @Synchronized
    fun loadAll() {
        rooms.listFiles()?.filter { it.isDirectory && RoomProfiles.isAgentId(it.name) }?.forEach { of(it.name) }
        publish()
    }

    /** The room is gone, and its lists with it (they lived in its folder). */
    @Synchronized
    fun forget(agentId: String) {
        stored.remove(agentId)
        publish()
    }

    private fun of(agentId: String): Stored = stored.getOrPut(agentId) { read(agentId) }

    private fun read(agentId: String): Stored {
        val file = storeFile(agentId)
        if (!file.isFile) return Stored()
        return try {
            AppJson.decodeFromString(Stored.serializer(), file.readText())
        } catch (unreadable: SerializationException) {
            Stored()
        } catch (unreadable: IllegalArgumentException) {
            Stored()
        } catch (unreadable: IOException) {
            Stored()
        }
    }

    private fun save(agentId: String, value: Stored) {
        stored[agentId] = value
        val file = storeFile(agentId)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.part")
        temporary.writeText(AppJson.encodeToString(Stored.serializer(), value))
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        publish()
    }

    private fun publish() {
        val byRoom = stored.toSortedMap().values
        mutablePending.value = byRoom.flatMap { it.pending }
        mutableKept.value = byRoom.flatMap { it.kept }
    }

    private fun storeFile(agentId: String) = File(rooms, "$agentId/$STORE")

    companion object {
        const val STORE = "config-changes.json"
        private const val MAX_PENDING = 50
        private const val MAX_VALUE_CHARS = 16 * 1024
    }
}
