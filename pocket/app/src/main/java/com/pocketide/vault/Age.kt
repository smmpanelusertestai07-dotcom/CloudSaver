package com.pocketide.vault

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Locale

/** Why an age file could not be opened. Messages are plain sentences. */
sealed class AgeException(message: String) : IOException(message)

/** The header is malformed, or a stanza addressed to one of our identities is invalid. */
class AgeHeaderException(message: String) : AgeException(message)

/** None of the identities opens any recipient stanza. */
class AgeNoMatchException : AgeException("None of the keys on this phone opens this file.")

/** The header MAC does not match: the header was changed after it was written. */
class AgeHeaderMacException : AgeException("This file was changed after it was written.")

/** The payload is damaged, cut short, or has data after its end. */
class AgePayloadException(message: String) : AgeException(message)

/** An age X25519 recipient (public key), written as `age1…`. */
class AgeRecipient internal constructor(publicKey: ByteArray) {
    private val publicKey = publicKey.copyOf()

    init {
        require(publicKey.size == X25519.POINT_SIZE) { "An X25519 public key is 32 bytes" }
    }

    fun encoded(): String = Bech32.encode(RECIPIENT_PREFIX, publicKey)

    internal fun bytes(): ByteArray = publicKey.copyOf()

    /** A fresh X25519 stanza that wraps [fileKey] for this recipient. */
    internal fun wrap(fileKey: ByteArray, random: SecureRandom): Stanza {
        val ephemeral = ByteArray(X25519.SCALAR_SIZE).also(random::nextBytes)
        val share = ByteArray(X25519.POINT_SIZE).also { X25519.scalarMultBase(ephemeral, 0, it, 0) }
        val shared = ByteArray(X25519.POINT_SIZE)
        try {
            require(X25519.calculateAgreement(ephemeral, 0, publicKey, 0, shared, 0)) { "That age recipient is not valid." }
            val wrapKey = hkdfSha256(shared, share + publicKey, X25519_LABEL)
            val body = ChaChaPoly.seal(wrapKey, ZERO_NONCE, fileKey)
            wrapKey.fill(0)
            return Stanza(X25519_TYPE, listOf(AgeBase64.encode(share)), body)
        } finally {
            ephemeral.fill(0)
            shared.fill(0)
        }
    }

    override fun equals(other: Any?) = other is AgeRecipient && other.publicKey.contentEquals(publicKey)

    override fun hashCode() = publicKey.contentHashCode()

    override fun toString() = encoded()

    companion object {
        fun parse(text: String): AgeRecipient {
            val decoded = Bech32.decode(text.trim())
            require(decoded != null && decoded.hrp == RECIPIENT_PREFIX && decoded.data.size == X25519.POINT_SIZE) {
                "This is not an age recipient."
            }
            return AgeRecipient(decoded.data)
        }
    }
}

/** An age X25519 identity: 32 random bytes, written as `AGE-SECRET-KEY-1…`. */
class AgeIdentity private constructor(private val secret: ByteArray) {
    val recipient = AgeRecipient(ByteArray(X25519.POINT_SIZE).also { X25519.scalarMultBase(secret, 0, it, 0) })

    /** The 32 secret bytes; the caller wipes the copy. */
    fun bytes(): ByteArray = secret.copyOf()

    fun encoded(): String = Bech32.encode(SECRET_KEY_PREFIX, secret).uppercase(Locale.ROOT)

    /**
     * The file key from [stanza] when it is addressed to this identity, or null when it is not.
     * A malformed X25519 stanza, or one whose shared secret is all zeros, aborts the whole file.
     */
    internal fun unwrap(stanza: Stanza): ByteArray? {
        if (stanza.type != X25519_TYPE) return null
        if (stanza.args.size != 1) throw AgeHeaderException("An X25519 recipient in the file header is not valid.")
        val share = AgeBase64.decode(stanza.args[0])?.takeIf { it.size == X25519.POINT_SIZE }
            ?: throw AgeHeaderException("An X25519 recipient in the file header is not valid.")
        val shared = ByteArray(X25519.POINT_SIZE)
        try {
            if (!X25519.calculateAgreement(secret, 0, share, 0, shared, 0)) {
                throw AgeHeaderException("An X25519 recipient in the file header is not valid.")
            }
            // Checked before decrypting, as the spec asks, so the tag cannot be probed with other sizes.
            if (stanza.body.size != FILE_KEY_SIZE + ChaChaPoly.TAG_SIZE) {
                throw AgeHeaderException("An X25519 recipient in the file header is not valid.")
            }
            val wrapKey = hkdfSha256(shared, share + recipient.bytes(), X25519_LABEL)
            return ChaChaPoly.open(wrapKey, ZERO_NONCE, stanza.body).also { wrapKey.fill(0) }
        } finally {
            shared.fill(0)
        }
    }

    override fun equals(other: Any?) =
        other is AgeIdentity && org.bouncycastle.util.Arrays.constantTimeAreEqual(secret, other.secret)

    override fun hashCode() = recipient.hashCode()

    /** Never shows the secret. */
    override fun toString() = "AgeIdentity(${recipient.encoded()})"

    companion object {
        fun generate(random: SecureRandom): AgeIdentity =
            AgeIdentity(ByteArray(X25519.SCALAR_SIZE).also(random::nextBytes))

        fun fromBytes(bytes: ByteArray): AgeIdentity {
            require(bytes.size == X25519.SCALAR_SIZE) { "An age identity is 32 bytes" }
            return AgeIdentity(bytes.copyOf())
        }

        /** Parses `AGE-SECRET-KEY-1…`; like age itself, only the upper-case form is accepted. */
        fun parse(text: String): AgeIdentity {
            val decoded = Bech32.decode(text.trim())
            require(
                decoded != null &&
                    decoded.hrp == SECRET_KEY_PREFIX.uppercase(Locale.ROOT) &&
                    decoded.data.size == X25519.SCALAR_SIZE,
            ) { "This is not an age secret key." }
            return AgeIdentity(decoded.data)
        }
    }
}

/**
 * age v1 (age-encryption.org/v1) with X25519 recipients: a header that wraps a random 16-byte
 * file key for each recipient and ends with an HMAC, then the payload as 64 KiB
 * ChaCha20-Poly1305 STREAM chunks. Files interoperate with the reference `age` tool.
 */
object Age {
    const val CHUNK_SIZE = 64 * 1024

    private val defaultRandom by lazy { SecureRandom() }

    /** Encrypts [input] to [recipients] into [output]; [output] stays open. */
    fun encrypt(recipients: List<AgeRecipient>, input: InputStream, output: OutputStream, random: SecureRandom = defaultRandom) {
        val sink = AgeOutputStream(output, recipients, random)
        input.copyTo(sink, CHUNK_SIZE)
        sink.finish()
    }

    /**
     * Decrypts [input] with the first of [identities] that opens it into [output]. Every chunk is
     * authenticated before it is written; on an exception the caller discards what was written.
     */
    fun decrypt(identities: List<AgeIdentity>, input: InputStream, output: OutputStream) {
        AgeInputStream(input, identities).copyTo(output, CHUNK_SIZE)
    }

    fun encryptBytes(recipients: List<AgeRecipient>, plain: ByteArray, random: SecureRandom = defaultRandom): ByteArray {
        val out = ByteArrayOutputStream(plain.size + 256 + (plain.size / CHUNK_SIZE + 1) * ChaChaPoly.TAG_SIZE)
        encrypt(recipients, ByteArrayInputStream(plain), out, random)
        return out.toByteArray()
    }

    fun decryptBytes(identities: List<AgeIdentity>, encrypted: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(encrypted.size)
        decrypt(identities, ByteArrayInputStream(encrypted), out)
        return out.toByteArray()
    }
}

/**
 * Writes an age file: the header when created, then the payload chunk by chunk. A full chunk is
 * only sealed once more data follows, so the final chunk is never empty unless the whole
 * payload is. [finish] completes the file and leaves the underlying stream open.
 */
class AgeOutputStream(
    private val out: OutputStream,
    recipients: List<AgeRecipient>,
    random: SecureRandom,
) : OutputStream() {
    private val chunk = ByteArray(Age.CHUNK_SIZE)
    private var filled = 0
    private var finished = false
    private val stream: PayloadStream

    init {
        require(recipients.isNotEmpty()) { "An age file needs at least one recipient" }
        val fileKey = ByteArray(FILE_KEY_SIZE).also(random::nextBytes)
        try {
            out.write(AgeHeader.write(fileKey, recipients.map { it.wrap(fileKey, random) }))
            val nonce = ByteArray(PAYLOAD_NONCE_SIZE).also(random::nextBytes)
            out.write(nonce)
            stream = PayloadStream(fileKey, nonce)
        } finally {
            fileKey.fill(0)
        }
    }

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
        check(!finished) { "The age file is already finished" }
        var offset = off
        var remaining = len
        while (remaining > 0) {
            if (filled == chunk.size) {
                out.write(stream.seal(chunk, filled, last = false))
                filled = 0
            }
            val n = minOf(remaining, chunk.size - filled)
            System.arraycopy(b, offset, chunk, filled, n)
            filled += n
            offset += n
            remaining -= n
        }
    }

    /** Seals the final chunk. The file is complete afterwards; the underlying stream stays open. */
    fun finish() {
        if (finished) return
        out.write(stream.seal(chunk, filled, last = true))
        chunk.fill(0)
        finished = true
        out.flush()
    }

    override fun flush() = out.flush()

    override fun close() {
        try {
            finish()
        } finally {
            out.close()
        }
    }
}

/**
 * Reads an age file: the header, identity and header MAC are checked when it is created; the
 * payload is released one authenticated chunk at a time. The end of the file is checked too: a
 * missing final chunk, an empty final chunk after others, or trailing data are errors.
 */
class AgeInputStream(source: InputStream, identities: List<AgeIdentity>) : InputStream() {
    private val input = if (source is BufferedInputStream) source else BufferedInputStream(source, SEALED_CHUNK_SIZE)
    private val stream: PayloadStream
    private val sealed = ByteArray(SEALED_CHUNK_SIZE)
    private val plain = ByteArray(Age.CHUNK_SIZE)
    private var position = 0
    private var available = 0
    private var ended = false
    private var failure: AgeException? = null

    init {
        val header = AgeHeader.read(input)
        val fileKey = unwrapFileKey(header, identities)
        try {
            if (!header.macMatches(fileKey)) throw AgeHeaderMacException()
            val nonce = ByteArray(PAYLOAD_NONCE_SIZE)
            if (readFully(nonce) != nonce.size) throw AgeHeaderException("The file ends before its content starts.")
            stream = PayloadStream(fileKey, nonce)
        } finally {
            fileKey.fill(0)
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        while (position == available) {
            failure?.let { throw it }
            if (ended) return -1
            try {
                nextChunk()
            } catch (e: AgeException) {
                failure = e
                throw e
            }
        }
        val n = minOf(len, available - position)
        System.arraycopy(plain, position, b, off, n)
        position += n
        return n
    }

    override fun available(): Int = available - position

    override fun close() {
        plain.fill(0)
        input.close()
    }

    private fun nextChunk() {
        val n = readFully(sealed)
        if (n == 0) throw AgePayloadException("This file is cut short.")
        val full = n == sealed.size
        if (!full && n == ChaChaPoly.TAG_SIZE && !stream.atStart) {
            throw AgePayloadException("This file ends with an empty piece, which age does not allow.")
        }
        var last = !full
        var opened = stream.open(sealed, n, last, plain)
        if (opened < 0 && full) {
            // A full-length chunk may still be the final one.
            last = true
            opened = stream.open(sealed, n, last = true, into = plain)
        }
        if (opened < 0) throw AgePayloadException("This file is damaged.")
        position = 0
        available = opened
        ended = last
        // The final chunk is released like any other; data after it fails the next read.
        if (last && full && input.read() != -1) failure = AgePayloadException("This file has extra data after its end.")
    }

    private fun readFully(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n == -1) break
            total += n
        }
        return total
    }

    private companion object {
        const val SEALED_CHUNK_SIZE = Age.CHUNK_SIZE + ChaChaPoly.TAG_SIZE

        fun unwrapFileKey(header: AgeHeader, identities: List<AgeIdentity>): ByteArray {
            for (identity in identities) {
                for (stanza in header.stanzas) identity.unwrap(stanza)?.let { return it }
            }
            throw AgeNoMatchException()
        }
    }
}

/** The STREAM construction: key = HKDF(file key, nonce, "payload"), nonce = 11-byte counter ‖ last flag. */
internal class PayloadStream(fileKey: ByteArray, nonce: ByteArray) {
    private val key = KeyParameter(hkdfSha256(fileKey, nonce, "payload"))
    private val counter = ByteArray(ChaChaPoly.NONCE_SIZE)
    private val aead = ChaCha20Poly1305()

    /** True until the first chunk has been sealed or opened. */
    val atStart: Boolean get() = counter.all { it.toInt() == 0 }

    fun seal(plain: ByteArray, length: Int, last: Boolean): ByteArray {
        aead.init(true, AEADParameters(key, ChaChaPoly.TAG_SIZE * 8, nonce(last)))
        val out = ByteArray(length + ChaChaPoly.TAG_SIZE)
        val n = aead.processBytes(plain, 0, length, out, 0)
        aead.doFinal(out, n)
        advance()
        return out
    }

    /** Opens one chunk into [into]; returns its length, or -1 when the tag does not verify with this flag. */
    fun open(sealed: ByteArray, length: Int, last: Boolean, into: ByteArray): Int {
        aead.init(false, AEADParameters(key, ChaChaPoly.TAG_SIZE * 8, nonce(last)))
        return try {
            val n = aead.processBytes(sealed, 0, length, into, 0)
            (n + aead.doFinal(into, n)).also { advance() }
        } catch (e: InvalidCipherTextException) {
            -1
        }
    }

    private fun nonce(last: Boolean): ByteArray =
        counter.copyOf().also { it[it.size - 1] = if (last) 1 else 0 }

    private fun advance() {
        for (i in counter.size - 2 downTo 0) {
            counter[i] = (counter[i] + 1).toByte()
            if (counter[i].toInt() != 0) return
        }
        throw AgePayloadException("This file is too large for age.")
    }
}

private const val SECRET_KEY_PREFIX = "age-secret-key-"
private const val RECIPIENT_PREFIX = "age"
private const val X25519_TYPE = "X25519"
private const val X25519_LABEL = "age-encryption.org/v1/X25519"
private const val FILE_KEY_SIZE = 16
private const val PAYLOAD_NONCE_SIZE = 16
private val ZERO_NONCE = ByteArray(ChaChaPoly.NONCE_SIZE)
