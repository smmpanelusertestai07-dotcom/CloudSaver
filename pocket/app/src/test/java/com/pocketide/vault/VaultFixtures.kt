package com.pocketide.vault

import java.security.MessageDigest

/** Test resources under `vault/` and the plaintext the interop fixtures were made from. */
object VaultFixtures {
    fun bytes(path: String): ByteArray {
        val stream = VaultFixtures::class.java.classLoader?.getResourceAsStream("vault/$path")
            ?: error("Missing test resource vault/$path")
        return stream.use { it.readBytes() }
    }

    fun text(path: String): String = bytes(path).toString(Charsets.UTF_8)

    /** The keys in an identity file or key copy (the same format), newest first. */
    fun identities(text: String): List<AgeIdentity> = KeyCopy.parse(text).map { it.identity }

    /** `key: value` lines, as in the fixture manifests. */
    fun fields(path: String): Map<String, String> =
        text(path).lineSequence()
            .filter { ": " in it && !it.startsWith("#") }
            .associate { it.substringBefore(": ") to it.substringAfter(": ") }

    /**
     * The fixture plaintext of [size] bytes: SHA-256("pocketide age fixture " ‖ counter) blocks,
     * the same generator the reference-tool fixtures were made with.
     */
    fun plain(size: Int): ByteArray {
        val out = ByteArray(size)
        var offset = 0
        var counter = 0
        while (offset < size) {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("pocketide age fixture ".toByteArray(Charsets.US_ASCII))
            digest.update(byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte()))
            val block = digest.digest()
            val n = minOf(block.size, size - offset)
            System.arraycopy(block, 0, out, offset, n)
            offset += n
            counter++
        }
        return out
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
