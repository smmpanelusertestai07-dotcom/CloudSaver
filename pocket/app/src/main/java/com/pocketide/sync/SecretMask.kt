package com.pocketide.sync

import com.pocketide.git.SecretPatterns
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Prompt histories (`history.jsonl`) keep everything the owner typed, including a key pasted by
 * mistake. Before such a file leaves the phone, anything that looks like a token or a key is
 * overwritten with `*`, character for character, so the file keeps its length and the pieces
 * keep their offsets. The phone's own copy is never changed.
 */
internal object SecretMask {
    /**
     * Shapes only a history needs, then every token the check-post knows. These run first: the
     * whole private-key block goes before the check-post's header-only match could hide it.
     */
    private val whole by lazy { ownShapes + SecretPatterns.tokenShapes }

    /** Every private-key label the check-post knows: "OPENSSH PRIVATE KEY", OpenPGP's "PGP PRIVATE KEY BLOCK"… */
    private const val KEY_LABEL = "(?:[A-Z0-9]+ )*PRIVATE KEY(?: BLOCK)?"

    /** One character of a JSON string, an escape counting as one: a pasted key's line breaks are `\n` there. */
    private const val IN_STRING = """(?:[^"\\\n]|\\.)"""

    /**
     * A pasted private key, header to footer. With no footer on the line (part of a key was
     * pasted), what follows the header in its JSON string goes too: the key is its body.
     */
    private val privateKey = Regex("-----BEGIN $KEY_LABEL-----(?:$IN_STRING*?-----END $KEY_LABEL-----|$IN_STRING*)")

    private val ownShapes = listOf(
        Regex("gh[pousr]_[A-Za-z0-9]{20,}"),
        Regex("github_pat_[A-Za-z0-9_]{20,}"),
        Regex("sk-[A-Za-z0-9_-]{20,}"),
        Regex("AIza[0-9A-Za-z_-]{30,}"),
        Regex("ya29\\.[0-9A-Za-z_-]{20,}"),
        Regex("1//0[0-9A-Za-z_-]{20,}"),
        Regex("A(KIA|SIA)[0-9A-Z]{16}"),
        Regex("xox[abprs]-[0-9A-Za-z-]{10,}"),
        Regex("AGE-SECRET-KEY-1[0-9A-Z]{50,}"),
        Regex("glpat-[0-9A-Za-z_-]{20,}"),
        Regex("npm_[0-9A-Za-z]{30,}"),
        Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
        privateKey,
    )

    /** `password=…`, `"api_key": "…"`: only the value is masked. */
    private val assignment = Regex("(?i)(password|passwd|secret|token|api[_-]?key)(\\\\?[\"']?\\s*[:=]\\s*\\\\?[\"']?)([^\\s\"'\\\\&,;}]{6,})")

    /** [bytes] with every secret-looking run replaced by `*` of the same length. */
    fun mask(bytes: ByteArray): ByteArray {
        // ISO-8859-1 maps each byte to one char and back, so lengths never change.
        val text = String(bytes, Charsets.ISO_8859_1)
        var out = text
        for (p in whole) out = p.replace(out) { "*".repeat(it.value.length) }
        out = assignment.replace(out) { m ->
            val value = m.groups[3] ?: return@replace m.value
            m.value.substring(0, value.range.first - m.range.first) + "*".repeat(value.value.length)
        }
        return if (out == text) bytes else out.toByteArray(Charsets.ISO_8859_1)
    }
}

/** Masks [source] line by line (at most [maxChunk] bytes at a time) with [SecretMask]. */
internal class SecretMaskingInputStream(source: InputStream, private val maxChunk: Int = MAX_CHUNK) : InputStream() {
    private val input = BufferedInputStream(source, BUFFER)
    private var chunk = ByteArray(0)
    private var pos = 0
    private var ended = false

    override fun read(): Int {
        if (!ensure()) return -1
        return chunk[pos++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!ensure()) return -1
        val n = minOf(len, chunk.size - pos)
        System.arraycopy(chunk, pos, b, off, n)
        pos += n
        return n
    }

    override fun close() = input.close()

    private fun ensure(): Boolean {
        while (pos >= chunk.size) {
            if (ended) return false
            val line = ByteArrayOutputStream()
            while (line.size() < maxChunk) {
                val c = input.read()
                if (c < 0) {
                    ended = true
                    break
                }
                line.write(c)
                if (c == '\n'.code) break
            }
            chunk = SecretMask.mask(line.toByteArray())
            pos = 0
        }
        return true
    }

    private companion object {
        const val MAX_CHUNK = 1 shl 20
        const val BUFFER = 64 * 1024
    }
}
