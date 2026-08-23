package app.cloudsaver.core.logic

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected settings backup.
 *
 * The file is encrypted on this device with a key derived from the user's
 * password only - nothing is stored anywhere else, so a lost password means a
 * lost file. That is the point: the backup can safely sit in any cloud drive.
 *
 * Layout: "CSB1" | salt(16) | iv(12) | AES-256-GCM ciphertext+tag.
 * The header is authenticated as AAD so a tampered file fails to open.
 */
object SecureBackup {

    private val MAGIC = byteArrayOf('C'.code.toByte(), 'S'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    /** OWASP guidance for PBKDF2-HMAC-SHA256; ~0.3 s on a mid-range phone. */
    const val ITERATIONS = 210_000

    const val MIN_PASSWORD_LENGTH = 8

    class WrongPasswordException : GeneralSecurityException("Wrong password or damaged file")

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size > MAGIC.size + SALT_LEN + IV_LEN &&
            MAGIC.indices.all { bytes[it] == MAGIC[it] }

    fun encrypt(plaintext: ByteArray, password: CharArray, random: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            deriveKey(password, salt),
            GCMParameterSpec(TAG_BITS, iv)
        )
        cipher.updateAAD(MAGIC)
        val body = cipher.doFinal(plaintext)
        return MAGIC + salt + iv + body
    }

    fun decrypt(blob: ByteArray, password: CharArray): ByteArray {
        if (!isEncrypted(blob)) throw IllegalArgumentException("Not a CloudSaver backup file")
        val salt = blob.copyOfRange(MAGIC.size, MAGIC.size + SALT_LEN)
        val iv = blob.copyOfRange(MAGIC.size + SALT_LEN, MAGIC.size + SALT_LEN + IV_LEN)
        val body = blob.copyOfRange(MAGIC.size + SALT_LEN + IV_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(password, salt),
            GCMParameterSpec(TAG_BITS, iv)
        )
        cipher.updateAAD(MAGIC)
        return try {
            cipher.doFinal(body)
        } catch (e: GeneralSecurityException) {
            // AEAD cannot tell "wrong key" from "edited file" - both are fatal.
            throw WrongPasswordException()
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** Rough strength hint for the password field. */
    enum class Strength { TOO_SHORT, WEAK, FAIR, STRONG }

    fun strengthOf(password: String): Strength {
        if (password.length < MIN_PASSWORD_LENGTH) return Strength.TOO_SHORT
        var classes = 0
        if (password.any { it.isLowerCase() }) classes++
        if (password.any { it.isUpperCase() }) classes++
        if (password.any { it.isDigit() }) classes++
        if (password.any { !it.isLetterOrDigit() }) classes++
        return when {
            password.length >= 14 && classes >= 3 -> Strength.STRONG
            password.length >= 11 || classes >= 3 -> Strength.FAIR
            else -> Strength.WEAK
        }
    }
}
