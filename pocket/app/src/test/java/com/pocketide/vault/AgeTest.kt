package com.pocketide.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

class AgeTest {
    private val random = SecureRandom()
    private val identity = AgeIdentity.generate(random)
    private val recipient = identity.recipient

    @Test
    fun `the spec's example identity gives the spec's example recipient`() {
        // Both strings are from the age specification; `age-keygen -y` gives the same recipient.
        val example = AgeIdentity.parse("AGE-SECRET-KEY-1GFPYYSJZGFPYYSJZGFPYYSJZGFPYYSJZGFPYYSJZGFPYYSJZGFPQ4EGAEX")
        assertEquals("age1zvkyg2lqzraa2lnjvqej32nkuu0ues2s82hzrye869xeexvn73equnujwj", example.recipient.encoded())
    }

    @Test
    fun `identities and recipients round-trip through their text form`() {
        val text = identity.encoded()
        assertTrue(text.startsWith("AGE-SECRET-KEY-1"))
        assertEquals(text.uppercase(), text)
        assertEquals(identity, AgeIdentity.parse(text))
        assertEquals(recipient, AgeRecipient.parse(recipient.encoded()))
        assertTrue(recipient.encoded().startsWith("age1"))
        assertFalse("toString never shows the secret", identity.toString().contains(text))
    }

    @Test
    fun `broken Bech32 is refused`() {
        val text = identity.encoded()
        val lastChar = text.last()
        val badChecksum = text.dropLast(1) + (if (lastChar == 'Q') 'P' else 'Q')
        val mixedCase = text.take(20) + text.drop(20).lowercase()
        for (bad in listOf(badChecksum, mixedCase, text.lowercase(), recipient.encoded(), "AGE-SECRET-KEY-1", "", "not a key")) {
            assertThrows(bad, IllegalArgumentException::class.java) { AgeIdentity.parse(bad) }
        }
        // Like age, recipients are lower case only, and an identity is not a recipient.
        assertThrows(IllegalArgumentException::class.java) { AgeRecipient.parse(recipient.encoded().uppercase()) }
        assertThrows(IllegalArgumentException::class.java) { AgeRecipient.parse(text) }
        assertNull(Bech32.decode("age1" + "q".repeat(3)))
    }

    @Test
    fun `an age-keygen identity file is accepted as a key copy`() {
        val file = "# created: 2026-09-24T12:00:00Z\n# public key: ${recipient.encoded()}\n\n${identity.encoded()}\n"
        assertEquals(listOf(identity), VaultFixtures.identities(file))
    }

    @Test
    fun `sizes around chunk boundaries round-trip, as bytes and as streams`() {
        for (size in listOf(0, 1, 16, 65_535, 65_536, 65_537, 131_072, 131_073, 200_000)) {
            val plain = ByteArray(size).also(random::nextBytes)
            val sealed = Age.encryptBytes(listOf(recipient), plain, random)
            assertArrayEquals("size $size", plain, Age.decryptBytes(listOf(identity), sealed))
            val out = ByteArrayOutputStream()
            Age.decrypt(listOf(identity), ByteArrayInputStream(sealed), out)
            assertArrayEquals("stream, size $size", plain, out.toByteArray())
        }
    }

    @Test
    fun `the payload is 64 KiB chunks, and a full final chunk is not followed by an empty one`() {
        val headerAndNonce = Age.encryptBytes(listOf(recipient), ByteArray(0), random).size - 16
        fun overhead(size: Int) = Age.encryptBytes(listOf(recipient), ByteArray(size), random).size - size - headerAndNonce
        assertEquals(16, overhead(0))
        assertEquals(16, overhead(65_536))
        assertEquals(32, overhead(65_537))
        assertEquals(32, overhead(131_072))
    }

    @Test
    fun `writing in small pieces gives a file that opens the same`() {
        val plain = ByteArray(150_000).also(random::nextBytes)
        val out = ByteArrayOutputStream()
        val stream = AgeOutputStream(out, listOf(recipient), random)
        var offset = 0
        while (offset < plain.size) {
            val n = minOf(1 + offset % 7_000, plain.size - offset)
            if (n == 1) stream.write(plain[offset].toInt()) else stream.write(plain, offset, n)
            offset += n
        }
        stream.finish()
        assertArrayEquals(plain, Age.decryptBytes(listOf(identity), out.toByteArray()))
    }

    @Test
    fun `reading byte by byte and in odd sizes gives the same plaintext`() {
        val plain = ByteArray(70_000).also(random::nextBytes)
        val sealed = Age.encryptBytes(listOf(recipient), plain, random)
        val stream = AgeInputStream(ByteArrayInputStream(sealed), listOf(identity))
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(9_999)
        while (true) {
            val one = stream.read()
            if (one == -1) break
            out.write(one)
            val n = stream.read(buffer)
            if (n == -1) break
            out.write(buffer, 0, n)
        }
        assertArrayEquals(plain, out.toByteArray())
        assertEquals(-1, stream.read())
    }

    @Test
    fun `the header is the version line, an X25519 stanza and the MAC line`() {
        val sealed = Age.encryptBytes(listOf(recipient), "hello".toByteArray(), random)
        val header = sealed.toString(Charsets.ISO_8859_1).substringBefore("\n---") + "\n---"
        val lines = header.lines()
        assertEquals("age-encryption.org/v1", lines[0])
        assertTrue(lines[1].matches(Regex("""-> X25519 [A-Za-z0-9+/]{43}""")))
        assertTrue(lines[2].matches(Regex("""[A-Za-z0-9+/]{43}""")))
        val macLine = sealed.toString(Charsets.ISO_8859_1).substringAfter("\n---").substringBefore("\n")
        assertTrue(macLine.matches(Regex(""" [A-Za-z0-9+/]{43}""")))
    }

    @Test
    fun `key history - a file for an older key opens when that key is among the identities`() {
        val older = AgeIdentity.generate(random)
        val sealed = Age.encryptBytes(listOf(older.recipient), "from last year".toByteArray(), random)
        assertEquals("from last year", Age.decryptBytes(listOf(identity, older), sealed).toString(Charsets.UTF_8))
        assertThrows(AgeNoMatchException::class.java) { Age.decryptBytes(listOf(identity), sealed) }
    }

    @Test
    fun `a file for two recipients opens with either identity`() {
        val other = AgeIdentity.generate(random)
        val sealed = Age.encryptBytes(listOf(recipient, other.recipient), "both".toByteArray(), random)
        assertEquals("both", Age.decryptBytes(listOf(identity), sealed).toString(Charsets.UTF_8))
        assertEquals("both", Age.decryptBytes(listOf(other), sealed).toString(Charsets.UTF_8))
    }

    @Test
    fun `a low-order recipient is refused before anything is written`() {
        val zeroPoint = AgeRecipient.parse(Bech32.encode("age", ByteArray(32)))
        assertThrows(IllegalArgumentException::class.java) { Age.encryptBytes(listOf(zeroPoint), ByteArray(1), random) }
    }

    @Test
    fun `a changed header is refused`() {
        val sealed = Age.encryptBytes(listOf(recipient), ByteArray(100), random)
        val text = sealed.toString(Charsets.ISO_8859_1)
        // Change one character of the X25519 share, of the wrapped file key, and of the MAC.
        for (offset in listOf(text.indexOf("X25519 ") + 10, text.indexOf('\n', 30) + 5, text.indexOf("--- ") + 10)) {
            val changed = sealed.copyOf()
            changed[offset] = if (changed[offset] == 'A'.code.toByte()) 'B'.code.toByte() else 'A'.code.toByte()
            assertThrows("offset $offset", AgeException::class.java) { Age.decryptBytes(listOf(identity), changed) }
        }
    }

    @Test
    fun `a different but well-formed MAC is a MAC failure`() {
        val sealed = Age.encryptBytes(listOf(recipient), ByteArray(10), random)
        val text = sealed.toString(Charsets.ISO_8859_1)
        val macStart = text.indexOf("--- ") + 4
        val otherMac = AgeBase64.encode(ByteArray(32) { 1 })
        val changed = text.substring(0, macStart) + otherMac + text.substring(macStart + 43)
        assertThrows(AgeHeaderMacException::class.java) { Age.decryptBytes(listOf(identity), changed.toByteArray(Charsets.ISO_8859_1)) }
    }

    @Test
    fun `a changed payload byte is refused`() {
        val sealed = Age.encryptBytes(listOf(recipient), ByteArray(70_000), random)
        for (fromEnd in listOf(1, 20, 5_000, 69_000)) {
            val changed = sealed.copyOf()
            changed[changed.size - fromEnd] = (changed[changed.size - fromEnd].toInt() xor 1).toByte()
            assertThrows("byte $fromEnd from the end", AgePayloadException::class.java) { Age.decryptBytes(listOf(identity), changed) }
        }
    }

    @Test
    fun `a cut file is refused, also exactly at a chunk boundary`() {
        val sealed = Age.encryptBytes(listOf(recipient), ByteArray(131_073), random)
        val payloadStart = sealed.size - 131_073 - 3 * 16
        for (keep in listOf(sealed.size - 1, payloadStart + 65_552, payloadStart + 2 * 65_552, payloadStart, payloadStart - 3)) {
            assertThrows("kept $keep of ${sealed.size}", AgeException::class.java) {
                Age.decryptBytes(listOf(identity), sealed.copyOf(keep))
            }
        }
    }

    @Test
    fun `swapped chunks are refused`() {
        val sealed = Age.encryptBytes(listOf(recipient), ByteArray(200_000).also(random::nextBytes), random)
        val payloadStart = sealed.size - 200_000 - 4 * 16
        val chunk = 65_552
        val swapped = sealed.copyOf()
        System.arraycopy(sealed, payloadStart, swapped, payloadStart + chunk, chunk)
        System.arraycopy(sealed, payloadStart + chunk, swapped, payloadStart, chunk)
        assertThrows(AgePayloadException::class.java) { Age.decryptBytes(listOf(identity), swapped) }
    }

    @Test
    fun `data after the end is refused`() {
        for (size in listOf(10, 65_536)) {
            val sealed = Age.encryptBytes(listOf(recipient), ByteArray(size), random)
            assertThrows(AgePayloadException::class.java) { Age.decryptBytes(listOf(identity), sealed + byteArrayOf(0)) }
        }
    }

    @Test
    fun `a stream stops at the damaged chunk and has released only verified chunks`() {
        val plain = ByteArray(140_000).also(random::nextBytes)
        val sealed = Age.encryptBytes(listOf(recipient), plain, random)
        sealed[sealed.size - 100] = (sealed[sealed.size - 100].toInt() xor 1).toByte()
        val out = ByteArrayOutputStream()
        assertThrows(AgePayloadException::class.java) { Age.decrypt(listOf(identity), ByteArrayInputStream(sealed), out) }
        assertArrayEquals(plain.copyOf(2 * 65_536), out.toByteArray())
    }

    @Test
    fun `every file gets a new file key and nonce`() {
        val a = Age.encryptBytes(listOf(recipient), ByteArray(64), random)
        val b = Age.encryptBytes(listOf(recipient), ByteArray(64), random)
        assertNotEquals(a.toList(), b.toList())
    }
}
