package com.pocketide.vault

import org.bouncycastle.crypto.engines.Salsa20Engine
import org.bouncycastle.math.ec.rfc7748.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64

class SealedBoxTest {
    private val random = SecureRandom()
    private val fixture = VaultFixtures.fields("sealedbox/pynacl.txt")
    private fun hex(name: String) = VaultFixtures.hex(fixture.getValue(name))

    @Test
    fun `HSalsa20 of the RFC 7748 shared secret matches libsodium's crypto_box_beforenm`() {
        val shared = ByteArray(32)
        X25519.scalarMult(hex("alice secret"), 0, hex("bob public"), 0, shared, 0)
        assertArrayEquals(hex("shared"), shared)
        assertArrayEquals(hex("beforenm"), HSalsa20.derive(shared, ByteArray(16)))
    }

    @Test
    fun `HSalsa20 equals the Salsa20 core with its final addition taken away`() {
        // An independent derivation: BouncyCastle's Salsa20 core adds the input back at the end.
        repeat(20) {
            val key = ByteArray(32).also(random::nextBytes)
            val input = ByteArray(16).also(random::nextBytes)
            val state = IntArray(16)
            val words = { bytes: ByteArray -> ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().let { b -> IntArray(b.remaining()) { b.get(it) } } }
            val k = words(key)
            val n = words(input)
            intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574).forEachIndexed { i, c -> state[i * 5] = c }
            for (i in 0 until 4) {
                state[1 + i] = k[i]
                state[11 + i] = k[4 + i]
                state[6 + i] = n[i]
            }
            val core = IntArray(16)
            Salsa20Engine.salsaCore(20, state, core)
            val expected = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            intArrayOf(0, 5, 10, 15, 6, 7, 8, 9).forEach { expected.putInt(core[it] - state[it]) }
            assertArrayEquals(expected.array(), HSalsa20.derive(key, input))
        }
    }

    @Test
    fun `a box sealed by PyNaCl opens`() {
        val opened = SealedBox.open(hex("public key"), hex("secret key"), hex("sealed"))
        assertArrayEquals(hex("message"), opened)
    }

    @Test
    fun `the box this code sealed, which PyNaCl opened, still opens`() {
        val opened = SealedBox.open(hex("public key"), hex("secret key"), hex("ours sealed"))
        assertArrayEquals(hex("ours message"), opened)
    }

    @Test
    fun `GitHub's form takes and gives standard base64`() {
        val base64 = Base64.getEncoder()
        val sealed = SealedBox.sealBase64(base64.encodeToString(hex("public key")), "ghp_example".toByteArray())
        assertFalse(sealed.contains('\n'))
        val raw = Base64.getDecoder().decode(sealed)
        assertEquals(SealedBox.OVERHEAD + "ghp_example".length, raw.size)
        assertEquals("ghp_example", SealedBox.open(hex("public key"), hex("secret key"), raw).toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) { SealedBox.sealBase64("not base64!", ByteArray(1)) }
        assertThrows(IllegalArgumentException::class.java) { SealedBox.sealBase64(base64.encodeToString(ByteArray(16)), ByteArray(1)) }
    }

    @Test
    fun `seal then open gives the message back in libsodium's layout`() {
        val secret = ByteArray(32).also(random::nextBytes)
        val public = ByteArray(32).also { X25519.scalarMultBase(secret, 0, it, 0) }
        for (size in listOf(0, 1, 47, 1_000)) {
            val message = ByteArray(size).also(random::nextBytes)
            val sealed = SealedBox.seal(public, message)
            assertEquals(SealedBox.OVERHEAD + size, sealed.size)
            assertArrayEquals(message, SealedBox.open(public, secret, sealed))
        }
    }

    @Test
    fun `a changed byte or the wrong key is refused`() {
        val secret = ByteArray(32).also(random::nextBytes)
        val public = ByteArray(32).also { X25519.scalarMultBase(secret, 0, it, 0) }
        val sealed = SealedBox.seal(public, "a secret value".toByteArray())
        for (offset in listOf(0, 31, 32, 47, 48, sealed.size - 1)) {
            val changed = sealed.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertThrows("offset $offset", GeneralSecurityException::class.java) { SealedBox.open(public, secret, changed) }
        }
        val other = ByteArray(32).also(random::nextBytes)
        val otherPublic = ByteArray(32).also { X25519.scalarMultBase(other, 0, it, 0) }
        assertThrows(GeneralSecurityException::class.java) { SealedBox.open(otherPublic, other, sealed) }
        assertThrows(GeneralSecurityException::class.java) { SealedBox.open(public, secret, sealed.copyOf(40)) }
    }

    @Test
    fun `keys that are not usable are refused`() {
        assertThrows(IllegalArgumentException::class.java) { SealedBox.seal(ByteArray(31), ByteArray(1)) }
        // The all-zero point gives an all-zero shared secret, which libsodium refuses too.
        assertThrows(IllegalArgumentException::class.java) { SealedBox.seal(ByteArray(32), ByteArray(1)) }
    }
}
