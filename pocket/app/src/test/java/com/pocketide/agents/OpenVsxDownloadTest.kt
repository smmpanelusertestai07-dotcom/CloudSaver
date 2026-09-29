package com.pocketide.agents

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.random.Random

class OpenVsxDownloadTest {
    private val server = MockWebServer()
    private lateinit var vsx: OpenVsx

    @Before
    fun setUp() {
        server.start()
        vsx = OpenVsx(OkHttpClient(), base = server.url("/"))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `a signature file as large as Codex's is read`() {
        // Codex's .sigzip was 478 KB in September 2026: beside the 64-byte signature, a manifest of every file.
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signature = OpenVsxFixture.sign(byteArrayOf(1, 2, 3), keys)
        val sigzip = OpenVsxFixture.zip(
            mapOf(
                ".signature.manifest" to Random(5).nextBytes(600_000),
                ".signature.sig" to signature,
                ".signature.p7s" to ByteArray(0),
            ),
        )
        val pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(keys.public.encoded) + "\n-----END PUBLIC KEY-----\n"
        // The key is read first, then the signature.
        server.enqueue(MockResponse.Builder().body(pem).build())
        server.enqueue(MockResponse.Builder().body(Buffer().write(sigzip)).build())

        val (key, read) = runBlocking { vsx.signature(files()) }!!

        assertEquals(true, sigzip.size > 500_000)
        assertArrayEquals(keys.public.encoded.copyOfRange(12, 44), key)
        assertArrayEquals(signature, read)
    }

    @Test
    fun `an answer over its limit is refused`() {
        server.enqueue(MockResponse.Builder().body("x".repeat(2_000)).build())

        val refused = assertThrows(IOException::class.java) { runBlocking { vsx.sha256(server.url("/api/a/b/1/file/b.sha256").toString()) } }

        assertEquals("An answer from Open VSX was larger than expected", refused.message)
    }

    private fun files() = mapOf(
        "signature" to server.url("/api/a/b/1/file/b.sigzip").toString(),
        "publicKey" to server.url("/api/-/public-key/1").toString(),
    )
}
