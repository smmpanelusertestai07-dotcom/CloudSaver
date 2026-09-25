package com.pocketide.agents

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * Open VSX signs every published .vsix with its own Ed25519 key. The `.sigzip` beside a file
 * holds `.signature.sig` (the 64-byte signature over the whole .vsix) next to a manifest and an
 * empty `.p7s`; the key is served as a PEM "PUBLIC KEY" (X.509 SubjectPublicKeyInfo).
 * Checked against a real package: the signature verifies over the .vsix bytes themselves.
 */
internal object OpenVsxSignature {
    private const val SIGNATURE_ENTRY = ".signature.sig"
    private const val MAX_ENTRY_BYTES = 64 * 1024

    /** The DER prefix of an Ed25519 SubjectPublicKeyInfo (OID 1.3.101.112), before the 32 key bytes. */
    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    fun signatureFrom(sigzip: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(sigzip)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name != SIGNATURE_ENTRY) continue
                val bytes = readLimited(zip)
                if (bytes.size != Ed25519Stream.SIGNATURE_BYTES) throw IOException("The package signature has the wrong length")
                return bytes
            }
        }
        throw IOException("The package signature file holds no signature")
    }

    fun publicKeyFrom(pem: String): ByteArray {
        val body = pem.trim()
            .removePrefix("-----BEGIN PUBLIC KEY-----")
            .removeSuffix("-----END PUBLIC KEY-----")
            .filterNot(Char::isWhitespace)
        val der = try {
            Base64.getDecoder().decode(body)
        } catch (notBase64: IllegalArgumentException) {
            throw IOException("Open VSX's signing key could not be read")
        }
        val prefixMatches = der.size == ED25519_SPKI_PREFIX.size + Ed25519Stream.KEY_BYTES &&
            der.copyOfRange(0, ED25519_SPKI_PREFIX.size).contentEquals(ED25519_SPKI_PREFIX)
        if (!prefixMatches) throw IOException("Open VSX's signing key is not an Ed25519 key")
        return der.copyOfRange(ED25519_SPKI_PREFIX.size, der.size)
    }

    private fun readLimited(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_ENTRY_BYTES) throw IOException("The package signature file is too large")
        }
        return out.toByteArray()
    }
}
