package com.pocketide.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.SecureRandom

class HalfPasswordTest {
    private val random = SecureRandom()
    private val small = Argon2Cost(memoryKiB = 256, iterations = 1, parallelism = 1)
    private val half = ByteArray(32).also(random::nextBytes)
    private val salt = ByteArray(16) { it.toByte() }

    @Test
    fun `Argon2id matches the reference implementation`() {
        // Known answers from argon2-cffi 25.1.0 (the reference C code): hash_secret_raw(type=ID, version=19, hash_len=32).
        val cases = listOf(
            Triple("correct horse battery staple", Argon2Cost(256, 2, 1), "7989cae79eab72e4e4f4124a6acde0796b3cb91920807721145cf29a5a1daaa6"),
            Triple("पासवर्ड ✓", Argon2Cost(64, 1, 1), "bcf9bb6c9f0bd683e23fe5a9cbe78e5a1b2e5c3a8bd68ea481c606389bf4dd01"),
            // The default cost the app uses.
            Triple("correct horse battery staple", Argon2Cost.DEFAULT, "0d1a3c6523c8f06e4e0af9c515aa5b5448cfebd6838f2d52c3d8b6ef8ddc3c2e"),
        )
        for ((password, cost, expected) in cases) {
            val key = HalfPassword.derive(password.toCharArray(), cost, salt)
            assertEquals(password, expected, key.key.joinToString("") { "%02x".format(it) })
        }
    }

    @Test
    fun `a lone surrogate in the password is refused with a plain sentence`() {
        val refused = assertThrows(VaultException::class.java) {
            HalfPassword.derive("pass\uD83D".toCharArray(), Argon2Cost(64, 1, 1), ByteArray(16))
        }
        assertEquals(VaultText.PASSWORD_UNUSABLE, refused.message)
    }

    @Test
    fun `the default cost is the plan's 64 MiB, 3 passes, 1 lane`() {
        assertEquals(Argon2Cost(65_536, 3, 1), Argon2Cost.DEFAULT)
    }

    @Test
    fun `the right password unwraps the half and gives the key to keep`() {
        val key = HalfPassword.derive("open sesame".toCharArray(), small, random)
        val wrapped = HalfPassword.wrap(half, key, random)
        val unwrapped = HalfPassword.unwrap(wrapped, "open sesame".toCharArray())
        assertArrayEquals(half, unwrapped.half)
        assertArrayEquals(key.key, unwrapped.key.key)
        assertArrayEquals(half, HalfPassword.unwrap(wrapped, unwrapped.key))
    }

    @Test
    fun `a wrong password is a clear error`() {
        val wrapped = HalfPassword.wrap(half, HalfPassword.derive("open sesame".toCharArray(), small, random), random)
        val error = assertThrows(WrongPasswordException::class.java) { HalfPassword.unwrap(wrapped, "open sesame!".toCharArray()) }
        assertEquals("That password is not right. Check it and try again.", error.message)
    }

    @Test
    fun `the layout is versioned and carries its cost and salt`() {
        val key = HalfPassword.derive("pw".toCharArray(), small, salt)
        val wrapped = HalfPassword.wrap(half, key, random)
        assertEquals(HalfPassword.WRAPPED_SIZE, wrapped.size)
        val header = ByteBuffer.wrap(wrapped)
        assertEquals(1, header.get().toInt())
        assertEquals(256, header.int)
        assertEquals(1, header.int)
        assertEquals(1, header.get().toInt())
        assertArrayEquals(salt, ByteArray(16).also { header.get(it) })
        // A fresh nonce each time, so two wraps of the same half differ.
        assertFalse(wrapped.contentEquals(HalfPassword.wrap(half, key, random)))
    }

    @Test
    fun `a changed byte anywhere is refused`() {
        val key = HalfPassword.derive("pw".toCharArray(), small, random)
        val wrapped = HalfPassword.wrap(half, key, random)
        for (offset in listOf(9, 12, 30, 40, 85)) {
            val changed = wrapped.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            val result = runCatching { HalfPassword.unwrap(changed, "pw".toCharArray()) }
            assertTrue("offset $offset", result.exceptionOrNull() is VaultException)
        }
    }

    @Test
    fun `a hostile cost or version is refused before any work`() {
        val key = HalfPassword.derive("pw".toCharArray(), small, random)
        val wrapped = HalfPassword.wrap(half, key, random)
        val huge = wrapped.copyOf().also { ByteBuffer.wrap(it).putInt(1, 4 * 1024 * 1024) }
        val newer = wrapped.copyOf().also { it[0] = 2 }
        for (bad in listOf(huge, newer, wrapped.copyOf(40))) {
            assertThrows(DamagedKeyFile::class.java) { HalfPassword.unwrap(bad, "pw".toCharArray()) }
        }
    }

    @Test
    fun `a kept key does not fit a half wrapped after the password changed`() {
        val old = HalfPassword.derive("old".toCharArray(), small, random)
        val new = HalfPassword.derive("new".toCharArray(), small, random)
        val wrapped = HalfPassword.wrap(half, new, random)
        assertFalse(HalfPassword.madeWith(wrapped, old))
        assertNull(HalfPassword.unwrap(wrapped, old))
        assertTrue(HalfPassword.madeWith(wrapped, new))
    }
}
