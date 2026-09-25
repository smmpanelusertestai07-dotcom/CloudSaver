package com.pocketide.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Files made by the reference `age` tool open here, and files this code made (and the reference
 * tool opened in the build sandbox) still open. The manifest records how each was made.
 */
class AgeInteropTest {
    private val manifest = VaultFixtures.fields("age/fixtures.txt")
    private val identities = VaultFixtures.identities(VaultFixtures.text("age/identity.txt"))

    @Test
    fun `the fixture identity is the one the manifest names`() {
        assertEquals(manifest.getValue("recipient"), identities.single().recipient.encoded())
    }

    @Test
    fun `files made by the reference age tool decrypt to the fixture plaintext`() {
        val files = manifest.keys.filter { it.startsWith("ref-") }
        assertEquals(6, files.size)
        files.forEach(::assertOpens)
    }

    @Test
    fun `an ssh-ed25519 stanza from the reference tool is skipped, not taken for damage`() {
        val header = VaultFixtures.bytes("age/ref-mixed-100.age").toString(Charsets.ISO_8859_1).substringBefore("\n---")
        assertEquals(listOf("X25519", "X25519", "ssh-ed25519"), header.lines().filter { it.startsWith("-> ") }.map { it.split(' ')[1] })
        assertThrows(AgeNoMatchException::class.java) {
            Age.decryptBytes(listOf(AgeIdentity.generate(java.security.SecureRandom())), VaultFixtures.bytes("age/ref-mixed-100.age"))
        }
    }

    @Test
    fun `files made by this code, opened by the reference tool, still decrypt`() {
        val files = manifest.keys.filter { it.startsWith("ours-") }
        assertEquals(2, files.size)
        files.forEach(::assertOpens)
    }

    private fun assertOpens(name: String) {
        val (size, sha256) = manifest.getValue(name).split(' ')
        val sealed = VaultFixtures.bytes("age/$name")
        val plain = Age.decryptBytes(identities, sealed)
        assertEquals(name, size.toInt(), plain.size)
        assertEquals(name, sha256, VaultFixtures.sha256Hex(plain))
        assertArrayEquals(name, VaultFixtures.plain(size.toInt()), plain)
        val streamed = ByteArrayOutputStream()
        Age.decrypt(identities, ByteArrayInputStream(sealed), streamed)
        assertArrayEquals(name, plain, streamed.toByteArray())
    }
}
