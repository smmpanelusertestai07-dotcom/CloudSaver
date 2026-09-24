package com.pocketide.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream

/**
 * The official age test vectors (C2SP CCTV, "age/testdata", 0BSD/CC0) that use X25519: every
 * success, header, MAC, no-match and payload case must come out exactly as the testkit expects,
 * including the plaintext released before a payload failure.
 */
class AgeTestkitTest {
    private class Vector(val name: String, val fields: Map<String, List<String>>, val file: ByteArray) {
        val expect get() = fields.getValue("expect").single()
        val payloadHash get() = fields["payload"]?.single()
        val identities get() = fields["identity"].orEmpty().filter { it.startsWith("AGE-SECRET-KEY-1") }.map { AgeIdentity.parse(it) }
    }

    private fun load(name: String): Vector {
        val raw = VaultFixtures.bytes("testkit/$name")
        val split = (0 until raw.size - 1).first { raw[it] == '\n'.code.toByte() && raw[it + 1] == '\n'.code.toByte() }
        val fields = raw.copyOf(split).toString(Charsets.UTF_8).lines()
            .groupBy({ it.substringBefore(": ") }, { it.substringAfter(": ") })
        var file = raw.copyOfRange(split + 2, raw.size)
        if (fields["compressed"]?.single() == "zlib") file = InflaterInputStream(ByteArrayInputStream(file)).readBytes()
        return Vector(name, fields, file)
    }

    @Test
    fun `every X25519 vector in the age testkit gives the expected result`() {
        val names = VaultFixtures.text("testkit/index.txt").lines().filter { it.isNotBlank() }
        assertEquals(69, names.size)
        val problems = names.map(::load).mapNotNull(::check)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** Null when the vector behaves as expected, else what went wrong. */
    private fun check(vector: Vector): String? {
        val released = ByteArrayOutputStream()
        val failure = try {
            Age.decrypt(vector.identities, ByteArrayInputStream(vector.file), released)
            null
        } catch (e: AgeException) {
            e
        }
        val outcome = when (failure) {
            null -> "success"
            is AgeHeaderException -> "header failure"
            is AgeNoMatchException -> "no match"
            is AgeHeaderMacException -> "HMAC failure"
            is AgePayloadException -> "payload failure"
        }
        if (outcome != vector.expect) return "${vector.name}: expected ${vector.expect}, got $outcome (${failure?.message})"
        if (outcome != "success" && outcome != "payload failure") return null
        val hash = vector.payloadHash ?: return "${vector.name}: the vector has no payload hash"
        val got = VaultFixtures.sha256Hex(released.toByteArray())
        return if (got == hash) null else "${vector.name}: released plaintext does not match the payload hash"
    }
}
