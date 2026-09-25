package com.pocketide.rooms

import com.pocketide.core.AppDirs
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.security.MessageDigest

/**
 * Settings that can start a program in Claude's `~/.claude.json`: its own and each project's MCP
 * servers, and a project's approvals of its `.mcp.json` servers or of tools. That file also holds
 * Claude's account, so the app never reads it. room.py does the work in the room: it takes out
 * each such setting that PocketIDE did not add and the owner did not keep, and lists them in
 * [REPORT] in the room's bridge folder, each as its canonical text (ASCII JSON: scope, place,
 * name, value) with that text's SHA-256. The owner keeps one by its SHA-256 ([keepList]); room.py
 * puts it back only while what it took out still has that SHA-256, so what comes back is exactly
 * what the owner read.
 */
internal object ClaudeState {
    const val FILE = ".claude.json"
    const val REPORT = "claude-held.json"
    const val GUEST_REPORT = "${AppDirs.GUEST_BRIDGE}/$REPORT"
    const val REPORT_BYTES = 1024L * 1024
    private const val MAX_ITEMS = 100
    private const val PARTS = 4
    private const val PRINTABLE_FIRST = 0x20
    private const val PRINTABLE_LAST = 0x7e

    /** POCKETIDE_CLAUDE_KEEP for room.py: the SHA-256 of each setting the owner kept. */
    fun keepList(kept: List<Entry>): String = JsonArray(kept.map { JsonPrimitive(sha256(it.value)) }.distinct()).toString()

    /** What a report lists, each item checked against its SHA-256; anything else in it is skipped. */
    fun parse(report: String): List<Entry> {
        val items = parseJson(report) as? JsonArray ?: return emptyList()
        return items.take(MAX_ITEMS).mapNotNull { item -> (item as? JsonObject)?.let(::entry) }
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun entry(item: JsonObject): Entry? {
        val text = item.string("entry") ?: return null
        // room.py writes printable ASCII only: anything else did not come from it.
        if (text.any { it.code < PRINTABLE_FIRST || it.code > PRINTABLE_LAST }) return null
        if (item.string("digest") != sha256(text)) return null
        val parts = parseJson(text) as? JsonArray ?: return null
        if (parts.size != PARTS) return null
        val place = (parts[1] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
        val name = (parts[2] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val keepable = (item["keepable"] as? JsonPrimitive)?.booleanOrNull ?: false
        return Entry(place, name, text, keepable)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun parseJson(text: String): JsonElement? = try {
        Json.parseToJsonElement(text)
    } catch (unreadable: SerializationException) {
        null
    } catch (unreadable: IllegalArgumentException) {
        null
    }
}
