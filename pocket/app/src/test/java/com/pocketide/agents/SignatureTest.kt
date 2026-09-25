package com.pocketide.agents

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.random.Random

class SignatureTest {
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** RFC 8032 §7.1, tests 1 and 2. */
    @Test
    fun rfc8032VectorsVerify() {
        val empty = Ed25519Stream(
            hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"),
            hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
        )
        assertTrue(empty.verify())

        val oneByte = Ed25519Stream(
            hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"),
            hex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
        )
        oneByte.update(byteArrayOf(0x72))
        assertTrue(oneByte.verify())
    }

    @Test
    fun aMessageFedInPiecesVerifiesAndAnyChangeFails() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val message = Random(3).nextBytes(700_000)
        val signature = OpenVsxFixture.sign(message, keys)
        val publicKey = keys.public.encoded.copyOfRange(12, 44)

        val stream = Ed25519Stream(publicKey, signature)
        message.toList().chunked(65_536).forEach { stream.update(it.toByteArray()) }
        assertTrue(stream.verify())

        val changed = message.copyOf().also { it[123_456] = (it[123_456] + 1).toByte() }
        assertFalse(Ed25519Stream(publicKey, signature).apply { update(changed) }.verify())
        val otherKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded.copyOfRange(12, 44)
        assertFalse(Ed25519Stream(otherKey, signature).apply { update(message) }.verify())
    }

    @Test
    fun openVsxSignatureFilesAreRead() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(keys.public.encoded) + "\n-----END PUBLIC KEY-----\n"
        val signature = OpenVsxFixture.sign(byteArrayOf(1, 2, 3), keys)

        assertArrayEquals(keys.public.encoded.copyOfRange(12, 44), OpenVsxSignature.publicKeyFrom(pem))
        assertArrayEquals(signature, OpenVsxSignature.signatureFrom(OpenVsxFixture.sigzip(signature)))
    }

    @Test
    fun anythingElseIsRefused() {
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val rsaPem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(rsa.public.encoded) + "\n-----END PUBLIC KEY-----"
        assertThrows(IOException::class.java) { OpenVsxSignature.publicKeyFrom(rsaPem) }
        assertThrows(IOException::class.java) { OpenVsxSignature.publicKeyFrom("not a key") }
        assertThrows(IOException::class.java) { OpenVsxSignature.signatureFrom(OpenVsxFixture.zip(mapOf(".signature.sig" to ByteArray(10)))) }
        assertThrows(IOException::class.java) { OpenVsxSignature.signatureFrom(OpenVsxFixture.zip(mapOf("other" to ByteArray(64)))) }
    }
}
