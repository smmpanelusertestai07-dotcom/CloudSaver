package com.pocketide.rooms

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * JSON with comments and trailing commas, as VS Code's settings and Antigravity's MCP config
 * allow. Comments and trailing commas are removed (outside strings) before a strict parse.
 */
internal object Jsonc {
    val pretty = Json { prettyPrint = true }

    /** The object in [text]; an empty object for blank text; null when it is not a JSON object. */
    fun parseObject(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return JsonObject(emptyMap())
        val cleaned = strip(text)
        if (cleaned.isBlank()) return JsonObject(emptyMap())
        return try {
            Json.parseToJsonElement(cleaned) as? JsonObject
        } catch (unreadable: SerializationException) {
            null
        } catch (unreadable: IllegalArgumentException) {
            null
        }
    }

    fun write(value: JsonObject): String = pretty.encodeToString(JsonObject.serializer(), value) + "\n"

    fun strip(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> i = copyString(text, i, out)
                c == '/' && text.startsWith("//", i) -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) text.length else end + 2
                    out.append(' ')
                }
                c == ',' && nextSignificant(text, i + 1).let { it == '}' || it == ']' } -> i++
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun copyString(text: String, start: Int, out: StringBuilder): Int {
        out.append('"')
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            out.append(c)
            i++
            if (c == '\\' && i < text.length) {
                out.append(text[i])
                i++
            } else if (c == '"') {
                return i
            }
        }
        return i
    }

    /** The next character that is not white space or inside a comment, or null at the end. */
    private fun nextSignificant(text: String, from: Int): Char? {
        var i = from
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                text.startsWith("//", i) -> while (i < text.length && text[i] != '\n') i++
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) text.length else end + 2
                }
                else -> return c
            }
        }
        return null
    }
}
