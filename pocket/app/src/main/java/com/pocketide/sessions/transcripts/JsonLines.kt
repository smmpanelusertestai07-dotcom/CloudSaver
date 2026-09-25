package com.pocketide.sessions.transcripts

import com.pocketide.core.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.Closeable
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Reads JSON Lines with bounded memory whatever the file holds. Agents store pasted images and
 * screenshots as base64 strings of several megabytes inside single lines, so every JSON string
 * longer than [maxString] bytes is cut short while it is read; the line stays valid JSON. A line
 * that is still too long after that is skipped.
 */
internal class JsonLineReader(
    private val input: InputStream,
    private val maxString: Int = MAX_STRING_BYTES,
    private val maxLine: Int = MAX_LINE_BYTES,
) : Closeable {
    private val buffer = ByteArray(BUFFER_BYTES)
    private var pos = 0
    private var limit = 0
    private var line = ByteArray(INITIAL_LINE_BYTES)
    private var size = 0
    private var tooLong = false
    private var inString = false
    private var escaped = false
    private var hexLeft = 0
    private var stringBytes = 0
    private var skipping = false
    private var pending = 0L

    /** Bytes of every line handed out or skipped so far, newlines included. */
    var consumed: Long = 0
        private set

    /**
     * The next non-empty line, or null at the end. A last line without a newline may still be
     * being written by the agent, so it is returned only when [partialLast] is set.
     */
    fun next(partialLast: Boolean = false): String? {
        while (true) {
            if (pos == limit) {
                limit = input.read(buffer).coerceAtLeast(0)
                pos = 0
                if (limit == 0) return if (partialLast && pending > 0) finishLine() else null
            }
            val b = buffer[pos++]
            pending++
            if (b == NEWLINE) {
                val text = finishLine()
                if (text != null) return text
            } else {
                accept(b)
            }
        }
    }

    override fun close() = input.close()

    private fun finishLine(): String? {
        consumed += pending
        pending = 0
        val text = if (tooLong || size == 0) null else String(line, 0, size, Charsets.UTF_8)
        size = 0
        tooLong = false
        inString = false
        escaped = false
        hexLeft = 0
        skipping = false
        return text?.takeIf { it.isNotBlank() }
    }

    private fun accept(b: Byte) {
        if (tooLong) return
        if (!inString) {
            put(b)
            if (b == QUOTE) {
                inString = true
                stringBytes = 0
            }
            return
        }
        if (skipping) {
            when {
                escaped -> escaped = false
                b == BACKSLASH -> escaped = true
                b == QUOTE -> {
                    put(QUOTE)
                    inString = false
                    skipping = false
                }
            }
            return
        }
        when {
            escaped -> {
                put(b)
                escaped = false
                stringBytes++
                if (b == LETTER_U) hexLeft = UNICODE_ESCAPE_DIGITS
            }
            hexLeft > 0 -> {
                put(b)
                hexLeft--
                stringBytes++
            }
            b == QUOTE -> {
                put(b)
                inString = false
            }
            stringBytes >= maxString -> {
                dropUnfinishedCharacter()
                ELLIPSIS.forEach(::put)
                skipping = true
                escaped = b == BACKSLASH
            }
            else -> {
                put(b)
                stringBytes++
                escaped = b == BACKSLASH
            }
        }
    }

    private fun put(b: Byte) {
        if (size >= maxLine) {
            tooLong = true
            return
        }
        if (size == line.size) line = line.copyOf(minOf(line.size * 2, maxLine))
        line[size++] = b
    }

    /** A cut inside a multi-byte UTF-8 character drops that character's first bytes too. */
    private fun dropUnfinishedCharacter() {
        var lead = size - 1
        while (lead >= 0 && size - lead <= MAX_CONTINUATION && line[lead].toInt() and 0xC0 == 0x80) lead--
        if (lead < 0) return
        val first = line[lead].toInt() and 0xFF
        val length = when {
            first >= 0xF0 -> 4
            first >= 0xE0 -> 3
            first >= 0xC0 -> 2
            else -> 1
        }
        if (size - lead < length) size = lead
    }

    companion object {
        const val MAX_STRING_BYTES = 64 * 1024
        const val MAX_LINE_BYTES = 4 * 1024 * 1024
        private const val BUFFER_BYTES = 64 * 1024
        private const val INITIAL_LINE_BYTES = 8 * 1024
        private const val UNICODE_ESCAPE_DIGITS = 4
        private const val MAX_CONTINUATION = 3
        private const val NEWLINE = '\n'.code.toByte()
        private const val QUOTE = '"'.code.toByte()
        private const val BACKSLASH = '\\'.code.toByte()
        private const val LETTER_U = 'u'.code.toByte()
        private val ELLIPSIS = "…".toByteArray(Charsets.UTF_8)
    }
}

/** One line as a JSON object, or null for anything else (agents' formats change between releases). */
internal fun parseObject(line: String): JsonObject? = try {
    AppJson.parseToJsonElement(line) as? JsonObject
} catch (malformed: IllegalArgumentException) {
    null
}

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

internal fun JsonElement?.asText(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement?.asLong(): Long? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

internal fun JsonElement?.asFlag(): Boolean = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

internal fun JsonObject.text(key: String): String? = this[key].asText()

internal fun JsonObject.obj(key: String): JsonObject? = this[key].asObject()

internal fun JsonObject.array(key: String): JsonArray? = this[key].asArray()

internal fun JsonObject.number(key: String): Long = this[key].asLong() ?: 0

/** An ISO 8601 time ("2026-09-24T10:15:30.123Z", or with an offset) in UTC milliseconds. */
internal fun isoMillis(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    return try {
        Instant.parse(text).toEpochMilli()
    } catch (notInstant: DateTimeParseException) {
        try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (notTime: DateTimeParseException) {
            null
        }
    }
}

/** Text for the transcript view: trimmed and cut at [max] characters. */
internal fun clip(text: String, max: Int = MAX_ENTRY_CHARS): String {
    val trimmed = text.trim()
    return if (trimmed.length <= max) trimmed else trimmed.take(max).trimEnd() + "…"
}

/** One line of text, for summaries and titles. */
internal fun oneLine(text: String, max: Int): String = clip(text.replace(WHITESPACE, " "), max)

private val WHITESPACE = Regex("\\s+")

/** Longest text of one transcript entry; longer messages end with "…". */
internal const val MAX_ENTRY_CHARS = 10_000
