package com.pocketide.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class KeyCopyTest {
    private val random = SecureRandom()
    private val keys = listOf(VaultKey(3, AgeIdentity.generate(random)), VaultKey(1, AgeIdentity.generate(random)))

    @Test
    fun `a copy lists every key newest first with its generation, and reads back`() {
        val text = KeyCopy.format(keys, savedAt = 1_758_700_000_000)
        val lines = text.lines()
        assertTrue(lines[0].startsWith("# PocketIDE key copy, saved "))
        assertTrue(lines.any { it.startsWith("# generation 3 (current), public key age1") })
        val parsed = KeyCopy.parse(text)
        assertEquals(listOf(3, 1), parsed.map { it.generation })
        assertEquals(keys.map { it.identity }, parsed.map { it.identity })
    }

    @Test(timeout = 5_000)
    fun `a four-million-character line is refused as not a key copy`() {
        val refused = assertThrows(VaultException::class.java) { KeyCopy.parse("AGE-SECRET-KEY-1" + "Q".repeat(4_000_000)) }
        assertEquals(VaultText.NOT_A_KEY_COPY, refused.message)
    }

    @Test
    fun `a copy tolerates Windows line ends and stray spaces from a notes app`() {
        val text = KeyCopy.format(keys, savedAt = 0).replace("\n", "  \r\n")
        assertEquals(keys.map { it.identity }, KeyCopy.parse(text).map { it.identity })
    }

    @Test
    fun `a bare key without comments is read without a generation`() {
        val parsed = KeyCopy.parse(keys[0].identity.encoded())
        assertEquals(null, parsed.single().generation)
    }

    @Test
    fun `anything that is not a key copy is refused with a plain sentence`() {
        for (bad in listOf("", "# only a comment", "hello", keys[0].identity.recipient.encoded())) {
            val error = assertThrows(VaultException::class.java) { KeyCopy.parse(bad) }
            assertEquals(VaultText.NOT_A_KEY_COPY, error.message)
        }
    }

    @Test
    fun `secret keys appear only on their own lines, never in comments`() {
        val text = KeyCopy.format(keys, savedAt = 0)
        val secretLines = text.lines().filter { it.startsWith("AGE-SECRET-KEY-1") }
        assertEquals(2, secretLines.size)
        assertFalse(text.lines().filter { it.startsWith("#") }.any { it.contains("AGE-SECRET-KEY") })
    }
}
