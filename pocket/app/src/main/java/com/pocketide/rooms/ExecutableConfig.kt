package com.pocketide.rooms

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One setting that can run code, as a config file holds it: where it is ([place], the keys that
 * lead to it joined by "."), its name there ([key]: a variable, a server, a hook event; empty for
 * a rule or a single value), and the setting itself ([value]: canonical JSON, or TOML lines).
 * [keepable] is false for the rare form PocketIDE could not write back without breaking the file.
 */
internal data class Entry(val place: String, val key: String, val value: String, val keepable: Boolean = true)

/**
 * A generated file rebuilt: its new [text] ([empty] when nothing is left to write, so the file
 * goes), and the settings that can run code it held that PocketIDE neither wrote nor was told to
 * keep ([added]): they are not in [text].
 */
internal data class Rebuilt(val text: String, val added: List<Entry> = emptyList(), val empty: Boolean = false)

/** Where, in a JSON config file, settings that can run code sit, and how they are told apart. */
internal sealed interface Slot {
    val path: List<String>

    val place: String get() = path.joinToString(".")

    /** The whole value is one setting (a command, a permission mode). */
    data class Value(override val path: List<String>) : Slot

    /** Each member of the object is one setting, by name (a variable, a server); [except] names belong to other slots. */
    data class Members(override val path: List<String>, val except: Set<String> = emptySet()) : Slot

    /** Each item of the array is one setting (a rule, a folder). */
    data class Items(override val path: List<String>) : Slot

    /** Each item of each array in the object is one setting, named by its array (hooks, by event). */
    data class Grouped(override val path: List<String>) : Slot
}

/**
 * Rebuilds the settings that can run code in a JSON config file: each [Slot] ends up holding
 * exactly the entries asked for (PocketIDE's own, then those the owner kept), whatever the file
 * held there before; everything else in the file stays as it was.
 */
internal object ExecutableJson {
    /** Every setting [root] holds in [slots]. A slot holding the wrong kind of value counts as one setting. */
    fun entries(root: JsonObject, slots: List<Slot>): List<Entry> = slots.flatMap { slot ->
        val here = at(root, slot.path)?.takeIf { it != JsonNull } ?: return@flatMap emptyList()
        when (slot) {
            is Slot.Value -> listOf(entry(slot, "", here))
            is Slot.Members -> (here as? JsonObject)?.filterKeys { it !in slot.except }?.map { (name, value) -> entry(slot, name, value) }
                ?: listOf(entry(slot, "", here))
            is Slot.Items -> (here as? JsonArray)?.map { entry(slot, "", it) } ?: listOf(entry(slot, "", here))
            is Slot.Grouped -> (here as? JsonObject)?.flatMap { (group, items) ->
                (items as? JsonArray)?.map { entry(slot, group, it) } ?: listOf(entry(slot, group, items))
            } ?: listOf(entry(slot, "", here))
        }
    }

    /**
     * What [found] holds that is neither PocketIDE's own ([ours]) nor kept by the owner ([kept]).
     * A member under one of PocketIDE's names is PocketIDE's, whatever it holds, and so is a
     * setting that runs PocketIDE's own tools ([isOurs]): both are simply written again as they
     * should be. Entries in a slot of the wrong shape (empty key where a name is needed) are added
     * too, so they go.
     */
    fun added(found: List<Entry>, ours: List<Entry>, kept: List<Entry>, slots: List<Slot>, isOurs: (Entry) -> Boolean = { false }): List<Entry> {
        val ourNames = ours.filter { entry -> slots.any { it is Slot.Members && it.place == entry.place } }.map { it.place to it.key }.toSet()
        return found.filter { entry ->
            ours.none { it.sameAs(entry) } && kept.none { it.sameAs(entry) } && (entry.place to entry.key) !in ourNames && !isOurs(entry)
        }.distinct()
    }

    /** [root] with each of [slots] holding exactly [wanted], in order; the first entry of a name wins, the last single value wins. */
    fun rebuild(root: JsonObject, slots: List<Slot>, wanted: List<Entry>): JsonObject = slots.fold(root) { result, slot ->
        put(result, slot.path, build(slot, wanted.filter { it.place == slot.place }, at(result, slot.path)))
    }

    /** JSON with every object's keys sorted, so the same setting always reads the same. */
    fun canonical(element: JsonElement): String = Json.encodeToString(JsonElement.serializer(), sorted(element))

    private fun entry(slot: Slot, key: String, value: JsonElement) = Entry(slot.place, key, canonical(value))

    private fun build(slot: Slot, entries: List<Entry>, current: JsonElement?): JsonElement? = when (slot) {
        is Slot.Value -> entries.lastOrNull()?.let { parse(it.value) }
        is Slot.Members -> {
            val members = LinkedHashMap<String, JsonElement>()
            (current as? JsonObject)?.forEach { (name, value) -> if (name in slot.except) members[name] = value }
            for (entry in entries) {
                val value = parse(entry.value)
                if (entry.key.isNotEmpty() && entry.key !in members && value != null) members[entry.key] = value
            }
            members.takeIf { it.isNotEmpty() || slot.path.isEmpty() }?.let(::JsonObject)
        }
        is Slot.Items -> entries.mapNotNull { parse(it.value) }.distinct().takeIf { it.isNotEmpty() }?.let(::JsonArray)
        is Slot.Grouped -> {
            val groups = LinkedHashMap<String, MutableList<JsonElement>>()
            for (entry in entries) {
                val value = parse(entry.value) ?: continue
                if (entry.key.isNotEmpty()) groups.getOrPut(entry.key) { mutableListOf() }.add(value)
            }
            groups.takeIf { it.isNotEmpty() }?.let { JsonObject(it.mapValues { (_, items) -> JsonArray(items.distinct()) }) }
        }
    }

    private fun at(root: JsonObject, path: List<String>): JsonElement? =
        path.fold(root as JsonElement?) { element, key -> (element as? JsonObject)?.get(key) }

    /** [root] with [value] at [path] (removed when null); objects on the way are made when needed. */
    private fun put(root: JsonObject, path: List<String>, value: JsonElement?): JsonObject {
        if (path.isEmpty()) return value as? JsonObject ?: JsonObject(emptyMap())
        val key = path.first()
        val child = if (path.size == 1) {
            value
        } else {
            val inner = root[key] as? JsonObject
            if (inner == null && value == null) return root
            put(inner ?: JsonObject(emptyMap()), path.drop(1), value)
        }
        val updated = LinkedHashMap(root)
        if (child == null) updated.remove(key) else updated[key] = child
        return JsonObject(updated)
    }

    private fun parse(value: String): JsonElement? = try {
        Json.parseToJsonElement(value)
    } catch (unreadable: SerializationException) {
        null
    } catch (unreadable: IllegalArgumentException) {
        null
    }

    private fun sorted(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { (key, value) -> key to sorted(value) })
        is JsonArray -> JsonArray(element.map(::sorted))
        is JsonPrimitive -> element
    }
}

/** The same setting: same place, name and content (keepable or not). */
internal fun Entry.sameAs(other: Entry) = place == other.place && key == other.key && value == other.value
