package com.pocketide.vault

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64

/**
 * libsodium's `crypto_box_seal`, which GitHub requires for Actions secrets: an ephemeral X25519
 * key, nonce = BLAKE2b-192(ephemeral public ‖ recipient public), XSalsa20-Poly1305.
 * Output: ephemeral public key (32) ‖ MAC (16) ‖ ciphertext.
 */
object SealedBox {
    private const val KEY_SIZE = 32
    private const val MAC_SIZE = 16
    private const val NONCE_SIZE = 24
    const val OVERHEAD = KEY_SIZE + MAC_SIZE

    private val random by lazy { SecureRandom() }

    fun seal(recipientPublicKey: ByteArray, message: ByteArray): ByteArray {
        require(recipientPublicKey.size == 32) { "An X25519 public key is 32 bytes" }
        val ephemeralSecret = ByteArray(KEY_SIZE).also(random::nextBytes)
        val ephemeralPublic = ByteArray(KEY_SIZE).also { X25519.scalarMultBase(ephemeralSecret, 0, it, 0) }
        val key = boxKey(ephemeralSecret, recipientPublicKey)
        ephemeralSecret.fill(0)
        requireNotNull(key) { "That public key is not a valid X25519 key" }
        try {
            return ephemeralPublic + secretBox(key, nonce(ephemeralPublic, recipientPublicKey), message)
        } finally {
            key.fill(0)
        }
    }

    /**
     * The form GitHub's Actions secrets API uses: the repository's public key (the `key` of
     * `GET …/actions/secrets/public-key`) comes in standard base64, and `encrypted_value` goes
     * back in standard base64 without line breaks.
     */
    fun sealBase64(recipientPublicKeyBase64: String, message: ByteArray): String {
        val publicKey = try {
            Base64.getDecoder().decode(recipientPublicKeyBase64.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("GitHub's public key for this repository is not valid base64", e)
        }
        return Base64.getEncoder().encodeToString(seal(publicKey, message))
    }

    /** `crypto_box_seal_open`; the app itself never opens sealed boxes, tests do. */
    internal fun open(recipientPublicKey: ByteArray, recipientSecretKey: ByteArray, sealed: ByteArray): ByteArray {
        if (sealed.size < OVERHEAD) throw GeneralSecurityException("The sealed box is too short")
        val ephemeralPublic = sealed.copyOf(KEY_SIZE)
        val key = boxKey(recipientSecretKey, ephemeralPublic) ?: throw GeneralSecurityException("The sealed box is not valid")
        try {
            val stream = xsalsa20(key, nonce(ephemeralPublic, recipientPublicKey))
            val macKey = keystream(stream, KEY_SIZE)
            val ciphertext = sealed.copyOfRange(OVERHEAD, sealed.size)
            val expected = poly1305(macKey, ciphertext)
            macKey.fill(0)
            if (!org.bouncycastle.util.Arrays.constantTimeAreEqual(expected, sealed.copyOfRange(KEY_SIZE, OVERHEAD))) {
                throw GeneralSecurityException("The sealed box was changed or is not for this key")
            }
            return ByteArray(ciphertext.size).also { stream.processBytes(ciphertext, 0, ciphertext.size, it, 0) }
        } finally {
            key.fill(0)
        }
    }

    /** `crypto_box_beforenm`: HSalsa20 of the X25519 shared secret; null for an all-zero secret. */
    private fun boxKey(secret: ByteArray, public: ByteArray): ByteArray? {
        val shared = ByteArray(KEY_SIZE)
        try {
            if (!X25519.calculateAgreement(secret, 0, public, 0, shared, 0)) return null
            return HSalsa20.derive(shared, ByteArray(16))
        } finally {
            shared.fill(0)
        }
    }

    private fun nonce(ephemeralPublic: ByteArray, recipientPublic: ByteArray): ByteArray {
        val blake = Blake2bDigest(NONCE_SIZE * 8)
        blake.update(ephemeralPublic, 0, ephemeralPublic.size)
        blake.update(recipientPublic, 0, recipientPublic.size)
        return ByteArray(NONCE_SIZE).also { blake.doFinal(it, 0) }
    }

    /** `crypto_secretbox`: the first 32 keystream bytes key Poly1305; the rest encrypt. Returns MAC ‖ ciphertext. */
    private fun secretBox(key: ByteArray, nonce: ByteArray, message: ByteArray): ByteArray {
        val stream = xsalsa20(key, nonce)
        val macKey = keystream(stream, KEY_SIZE)
        val ciphertext = ByteArray(message.size).also { stream.processBytes(message, 0, message.size, it, 0) }
        val mac = poly1305(macKey, ciphertext)
        macKey.fill(0)
        return mac + ciphertext
    }

    private fun xsalsa20(key: ByteArray, nonce: ByteArray) =
        XSalsa20Engine().apply { init(true, ParametersWithIV(KeyParameter(key), nonce)) }

    private fun keystream(stream: XSalsa20Engine, length: Int): ByteArray =
        ByteArray(length).also { stream.processBytes(ByteArray(length), 0, length, it, 0) }

    private fun poly1305(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Poly1305()
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        return ByteArray(MAC_SIZE).also { mac.doFinal(it, 0) }
    }
}

/**
 * HSalsa20 from the XSalsa20 paper: the Salsa20 core's 20 rounds over the constants, a 32-byte
 * key and a 16-byte input, without the final addition, keeping words 0, 5, 10, 15, 6, 7, 8, 9.
 * BouncyCastle only uses it inside XSalsa20, so NaCl's `crypto_box_beforenm` needs it here.
 */
internal object HSalsa20 {
    private val SIGMA = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574) // "expand 32-byte k"

    fun derive(key: ByteArray, input: ByteArray): ByteArray {
        require(key.size == 32 && input.size == 16) { "HSalsa20 takes a 32-byte key and a 16-byte input" }
        val x = IntArray(16)
        x[0] = SIGMA[0]
        x[5] = SIGMA[1]
        x[10] = SIGMA[2]
        x[15] = SIGMA[3]
        for (i in 0 until 4) {
            x[1 + i] = littleEndian(key, 4 * i)
            x[11 + i] = littleEndian(key, 16 + 4 * i)
            x[6 + i] = littleEndian(input, 4 * i)
        }
        repeat(10) {
            // Column round, then row round: one Salsa20 double round.
            quarterRound(x, 0, 4, 8, 12)
            quarterRound(x, 5, 9, 13, 1)
            quarterRound(x, 10, 14, 2, 6)
            quarterRound(x, 15, 3, 7, 11)
            quarterRound(x, 0, 1, 2, 3)
            quarterRound(x, 5, 6, 7, 4)
            quarterRound(x, 10, 11, 8, 9)
            quarterRound(x, 15, 12, 13, 14)
        }
        val out = ByteArray(32)
        intArrayOf(0, 5, 10, 15, 6, 7, 8, 9).forEachIndexed { i, word -> putLittleEndian(x[word], out, 4 * i) }
        x.fill(0)
        return out
    }

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[b] = x[b] xor (x[a] + x[d]).rotateLeft(7)
        x[c] = x[c] xor (x[b] + x[a]).rotateLeft(9)
        x[d] = x[d] xor (x[c] + x[b]).rotateLeft(13)
        x[a] = x[a] xor (x[d] + x[c]).rotateLeft(18)
    }

    private fun littleEndian(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun putLittleEndian(value: Int, out: ByteArray, offset: Int) {
        for (i in 0 until 4) out[offset + i] = (value ushr (8 * i)).toByte()
    }
}
