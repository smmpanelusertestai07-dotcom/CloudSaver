package com.pocketide.vault

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64

/** One recipient stanza: `-> type args…` and its binary body. */
internal class Stanza(val type: String, val args: List<String>, val body: ByteArray)

/** A parsed header: the stanzas, its MAC, and the exact bytes the MAC covers. */
internal class AgeHeader(val stanzas: List<Stanza>, val mac: ByteArray, private val macInput: ByteArray) {

    fun macMatches(fileKey: ByteArray): Boolean =
        org.bouncycastle.util.Arrays.constantTimeAreEqual(mac, headerMac(fileKey, macInput))

    companion object {
        private const val VERSION_LINE = "age-encryption.org/v1"
        private const val COLUMNS = 64
        private const val MAC_SIZE = 32
        /** Far above any real header; stops a hostile file from growing memory without end. */
        private const val MAX_HEADER_BYTES = 256 * 1024

        /** The header for [fileKey] wrapped to each stanza, with its MAC line. */
        fun write(fileKey: ByteArray, stanzas: List<Stanza>): ByteArray {
            val text = StringBuilder(VERSION_LINE).append('\n')
            for (stanza in stanzas) {
                text.append("->")
                (listOf(stanza.type) + stanza.args).forEach { text.append(' ').append(it) }
                text.append('\n').append(wrap(AgeBase64.encode(stanza.body))).append('\n')
            }
            text.append("---")
            val mac = headerMac(fileKey, text.toString().toByteArray(Charsets.US_ASCII))
            text.append(' ').append(AgeBase64.encode(mac)).append('\n')
            return text.toString().toByteArray(Charsets.US_ASCII)
        }

        /**
         * Reads a header strictly, byte by byte, so nothing of the payload is consumed. Anything
         * the format does not allow (CR, padding, non-canonical base64, long lines, an unknown
         * version, a stanza without its short final line) is rejected.
         */
        fun read(input: InputStream): AgeHeader {
            val raw = ByteArrayOutputStream()
            val first = readLine(input, raw) ?: throw AgeHeaderException("This file is empty.")
            if (first != VERSION_LINE) throw AgeHeaderException("This is not an age v1 file.")
            val stanzas = ArrayList<Stanza>()
            while (true) {
                val lineStart = raw.size()
                val line = readLine(input, raw) ?: throw AgeHeaderException("The file ends inside its header.")
                if (line.startsWith("---")) {
                    val mac = parseMacLine(line)
                    if (stanzas.isEmpty()) throw AgeHeaderException("The file header has no recipients.")
                    if (stanzas.size > 1 && stanzas.any { it.type == "scrypt" }) {
                        throw AgeHeaderException("A passphrase file must have exactly one recipient.")
                    }
                    return AgeHeader(stanzas, mac, raw.toByteArray().copyOf(lineStart + 3))
                }
                stanzas += readStanza(line, input, raw)
            }
        }

        private fun parseMacLine(line: String): ByteArray {
            val parts = line.split(' ')
            if (parts.size != 2 || parts[0] != "---") throw AgeHeaderException("The file header ends badly.")
            return AgeBase64.decode(parts[1])?.takeIf { it.size == MAC_SIZE }
                ?: throw AgeHeaderException("The file header ends badly.")
        }

        private fun readStanza(argumentLine: String, input: InputStream, raw: ByteArrayOutputStream): Stanza {
            val parts = argumentLine.split(' ')
            if (parts[0] != "->" || parts.size < 2 || parts.drop(1).any { it.isEmpty() }) {
                throw AgeHeaderException("A recipient line in the file header is not valid.")
            }
            val body = ByteArrayOutputStream()
            while (true) {
                val line = readLine(input, raw) ?: throw AgeHeaderException("The file ends inside its header.")
                val bytes = AgeBase64.decode(line)
                if (bytes == null || line.length > COLUMNS) {
                    throw AgeHeaderException("A recipient in the file header is not valid.")
                }
                body.write(bytes)
                // A stanza body always ends with a line shorter than 64 columns, even an empty one.
                if (line.length < COLUMNS) return Stanza(parts[1], parts.drop(2), body.toByteArray())
            }
        }

        /** One LF-terminated line of printable ASCII, without the LF; null at the very end of input. */
        private fun readLine(input: InputStream, raw: ByteArrayOutputStream): String? {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) {
                    if (line.isEmpty()) return null
                    throw AgeHeaderException("The file ends inside its header.")
                }
                raw.write(b)
                if (raw.size() > MAX_HEADER_BYTES) throw AgeHeaderException("The file header is too large.")
                if (b == '\n'.code) return line.toString()
                if (b !in 0x20..0x7e) throw AgeHeaderException("The file header has characters age does not allow.")
                line.append(b.toChar())
            }
        }

        private fun wrap(base64: String): String {
            val lines = base64.chunked(COLUMNS).toMutableList()
            if (base64.length % COLUMNS == 0) lines += ""
            return lines.joinToString("\n")
        }

        private fun headerMac(fileKey: ByteArray, macInput: ByteArray): ByteArray {
            val key = hkdfSha256(fileKey, salt = null, info = "header")
            val hmac = HMac(SHA256Digest())
            hmac.init(KeyParameter(key))
            hmac.update(macInput, 0, macInput.size)
            key.fill(0)
            return ByteArray(hmac.macSize).also { hmac.doFinal(it, 0) }
        }
    }
}

/** Standard base64 without padding, and only its canonical form, as the age spec requires. */
internal object AgeBase64 {
    private val encoder = Base64.getEncoder().withoutPadding()
    private val decoder = Base64.getDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    fun decode(text: String): ByteArray? {
        if (text.length % 4 == 1 || text.contains('=')) return null
        val bytes = try {
            decoder.decode(text)
        } catch (e: IllegalArgumentException) {
            return null
        }
        // Re-encoding catches non-zero unused bits in the last character.
        return bytes.takeIf { encode(it) == text }
    }
}

/** HKDF-SHA-256 with a 32-byte output; a null salt is HKDF's zero salt, which equals an empty one. */
internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: String): ByteArray {
    val generator = HKDFBytesGenerator(SHA256Digest())
    generator.init(HKDFParameters(ikm, salt, info.toByteArray(Charsets.US_ASCII)))
    return ByteArray(32).also { generator.generateBytes(it, 0, it.size) }
}

internal object ChaChaPoly {
    const val TAG_SIZE = 16
    const val NONCE_SIZE = 12

    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val aead = ChaCha20Poly1305()
        aead.init(true, AEADParameters(KeyParameter(key), TAG_SIZE * 8, nonce, aad))
        val out = ByteArray(plain.size + TAG_SIZE)
        val n = aead.processBytes(plain, 0, plain.size, out, 0)
        aead.doFinal(out, n)
        return out
    }

    /** The plaintext, or null when the tag does not verify. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray? {
        if (sealed.size < TAG_SIZE) return null
        val aead = ChaCha20Poly1305()
        aead.init(false, AEADParameters(KeyParameter(key), TAG_SIZE * 8, nonce, aad))
        val out = ByteArray(sealed.size - TAG_SIZE)
        return try {
            val n = aead.processBytes(sealed, 0, sealed.size, out, 0)
            aead.doFinal(out, n)
            out
        } catch (e: InvalidCipherTextException) {
            out.fill(0)
            null
        }
    }
}
