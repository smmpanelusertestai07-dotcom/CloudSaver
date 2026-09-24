package com.pocketide.vault

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.security.SecureRandom

/** Argon2id cost. */
internal data class Argon2Cost(val memoryKiB: Int, val iterations: Int, val parallelism: Int) {
    /** Bounds a hostile keyring file could otherwise use to exhaust the phone's memory or time. */
    val acceptable: Boolean
        get() = parallelism in 1..MAX_PARALLELISM &&
            iterations in 1..MAX_ITERATIONS &&
            memoryKiB in 8 * parallelism..MAX_MEMORY_KIB

    companion object {
        /** 64 MiB, 3 passes, 1 lane: about a second on a mid-range phone. */
        val DEFAULT = Argon2Cost(memoryKiB = 65_536, iterations = 3, parallelism = 1)
        const val MAX_MEMORY_KIB = 131_072
        const val MAX_ITERATIONS = 16
        const val MAX_PARALLELISM = 4
    }
}

/** The key derived from the extra password, with the cost and salt that made it. */
internal class PasswordKey(val cost: Argon2Cost, val salt: ByteArray, val key: ByteArray)

internal class UnwrappedHalf(val half: ByteArray, val key: PasswordKey)

/**
 * The optional extra password around Half G: Argon2id(password, salt) gives a 32-byte key, and
 * ChaCha20-Poly1305 with a random nonce seals the half. Version 1 layout, big-endian:
 * version (1) ‖ memory KiB (4) ‖ iterations (4) ‖ parallelism (1) ‖ salt (16) ‖ nonce (12) ‖ sealed half (48).
 * Everything before the sealed half is authenticated as associated data.
 */
internal object HalfPassword {
    private const val VERSION: Byte = 1
    private const val SALT_SIZE = 16
    private const val HEADER_SIZE = 1 + 4 + 4 + 1 + SALT_SIZE + ChaChaPoly.NONCE_SIZE
    const val WRAPPED_SIZE = HEADER_SIZE + KeySplit.KEY_SIZE + ChaChaPoly.TAG_SIZE

    fun derive(password: CharArray, cost: Argon2Cost, random: SecureRandom): PasswordKey =
        derive(password, cost, ByteArray(SALT_SIZE).also(random::nextBytes))

    fun derive(password: CharArray, cost: Argon2Cost, salt: ByteArray): PasswordKey {
        require(cost.acceptable && salt.size == SALT_SIZE) { "Unsupported Argon2 settings" }
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(cost.memoryKiB)
            .withIterations(cost.iterations)
            .withParallelism(cost.parallelism)
            .withSalt(salt)
            .build()
        val key = ByteArray(KeySplit.KEY_SIZE)
        Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password, key)
        parameters.clear()
        return PasswordKey(cost, salt.copyOf(), key)
    }

    fun wrap(half: ByteArray, key: PasswordKey, random: SecureRandom): ByteArray {
        val nonce = ByteArray(ChaChaPoly.NONCE_SIZE).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .put(VERSION)
            .putInt(key.cost.memoryKiB)
            .putInt(key.cost.iterations)
            .put(key.cost.parallelism.toByte())
            .put(key.salt)
            .put(nonce)
            .array()
        return header + ChaChaPoly.seal(key.key, nonce, half, aad = header)
    }

    /** Unwraps with the password, using the cost and salt in the header. Throws [WrongPasswordException]. */
    fun unwrap(wrapped: ByteArray, password: CharArray): UnwrappedHalf {
        val header = Header.parse(wrapped)
        val key = derive(password, header.cost, header.salt)
        val half = open(wrapped, header, key) ?: throw WrongPasswordException()
        return UnwrappedHalf(half, key)
    }

    /** Unwraps with a key this phone kept; null when it was not made with it or does not open. */
    fun unwrap(wrapped: ByteArray, key: PasswordKey): ByteArray? {
        val header = Header.parse(wrapped)
        if (!header.matches(key)) return null
        return open(wrapped, header, key)
    }

    /** True when [wrapped] was made with [key]'s password, cost and salt (false after a change on another phone). */
    fun madeWith(wrapped: ByteArray, key: PasswordKey): Boolean = Header.parse(wrapped).matches(key)

    private fun open(wrapped: ByteArray, header: Header, key: PasswordKey): ByteArray? =
        ChaChaPoly.open(key.key, header.nonce, wrapped.copyOfRange(HEADER_SIZE, wrapped.size), aad = wrapped.copyOf(HEADER_SIZE))

    private class Header(val cost: Argon2Cost, val salt: ByteArray, val nonce: ByteArray) {
        fun matches(key: PasswordKey) = cost == key.cost && salt.contentEquals(key.salt)

        companion object {
            fun parse(wrapped: ByteArray): Header {
                if (wrapped.size != WRAPPED_SIZE || wrapped[0] != VERSION) throw DamagedKeyFile(VaultText.GITHUB_HALF_DAMAGED)
                val buffer = ByteBuffer.wrap(wrapped, 1, HEADER_SIZE - 1)
                val cost = Argon2Cost(memoryKiB = buffer.int, iterations = buffer.int, parallelism = buffer.get().toInt() and 0xff)
                if (!cost.acceptable) throw DamagedKeyFile(VaultText.GITHUB_HALF_DAMAGED)
                val salt = ByteArray(SALT_SIZE).also { buffer.get(it) }
                val nonce = ByteArray(ChaChaPoly.NONCE_SIZE).also { buffer.get(it) }
                return Header(cost, salt, nonce)
            }
        }
    }
}
