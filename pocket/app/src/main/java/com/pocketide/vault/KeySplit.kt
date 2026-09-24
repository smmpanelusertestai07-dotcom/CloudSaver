package com.pocketide.vault

import java.security.SecureRandom

/**
 * The vault key as two halves: Half D is random and Half G = key XOR Half D. Each half alone is
 * uniformly random and says nothing about the key; together they give it back exactly.
 */
internal object KeySplit {
    const val KEY_SIZE = 32

    class Halves(val halfD: ByteArray, val halfG: ByteArray) {
        fun wipe() = wipe(halfD, halfG)
    }

    fun split(key: ByteArray, random: SecureRandom): Halves {
        val halfD = ByteArray(KEY_SIZE).also(random::nextBytes)
        return Halves(halfD, xor(key, halfD))
    }

    /** The Half G that pairs with a given Half D. */
    fun halfG(key: ByteArray, halfD: ByteArray): ByteArray = xor(key, halfD)

    fun join(halfD: ByteArray, halfG: ByteArray): ByteArray = xor(halfD, halfG)

    /** Overwrites key material that is no longer needed. */
    fun wipe(vararg arrays: ByteArray?) {
        for (array in arrays) array?.fill(0)
    }

    private fun xor(a: ByteArray, b: ByteArray): ByteArray {
        require(a.size == KEY_SIZE && b.size == KEY_SIZE) { "Keys and halves are $KEY_SIZE bytes" }
        return ByteArray(KEY_SIZE) { (a[it].toInt() xor b[it].toInt()).toByte() }
    }
}
