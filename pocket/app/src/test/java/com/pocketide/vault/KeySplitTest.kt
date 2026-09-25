package com.pocketide.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class KeySplitTest {
    private val random = SecureRandom()
    private val key = ByteArray(32).also(random::nextBytes)

    @Test
    fun `the two halves join back into the key`() {
        val halves = KeySplit.split(key, random)
        assertArrayEquals(key, KeySplit.join(halves.halfD, halves.halfG))
        assertArrayEquals(halves.halfG, KeySplit.halfG(key, halves.halfD))
    }

    @Test
    fun `each split is fresh and neither half is the key`() {
        val first = KeySplit.split(key, random)
        val second = KeySplit.split(key, random)
        assertFalse(first.halfD.contentEquals(second.halfD))
        assertFalse(first.halfG.contentEquals(second.halfG))
        assertFalse(first.halfD.contentEquals(key) || first.halfG.contentEquals(key))
        // A half from one split with a half from another gives something else entirely.
        assertFalse(KeySplit.join(first.halfD, second.halfG).contentEquals(key))
    }

    @Test
    fun `wiping zeroes every array given`() {
        val halves = KeySplit.split(key, random)
        halves.wipe()
        assertTrue(halves.halfD.all { it.toInt() == 0 } && halves.halfG.all { it.toInt() == 0 })
        val other = ByteArray(8) { 1 }
        KeySplit.wipe(other, null)
        assertTrue(other.all { it.toInt() == 0 })
    }

    @Test
    fun `only whole 32-byte keys and halves are accepted`() {
        assertThrows(IllegalArgumentException::class.java) { KeySplit.split(ByteArray(31), random) }
        assertThrows(IllegalArgumentException::class.java) { KeySplit.join(ByteArray(32), ByteArray(33)) }
    }
}
