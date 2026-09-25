package com.pocketide.github

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublicReleasesTest {
    @Test fun `the latest release is read with each asset's SHA-256 digest`() {
        val digest = "AB".repeat(32)
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().body(
                    """{"tag_name":"v4.139.0","prerelease":false,"assets":[""" +
                        """{"name":"a.tar.gz","size":10,"browser_download_url":"https://github.com/a.tar.gz","digest":"sha256:$digest"},""" +
                        """{"name":"b.tar.gz","size":20,"browser_download_url":"https://github.com/b.tar.gz","digest":"sha512:00"},""" +
                        """{"name":"c.tar.gz","size":30,"browser_download_url":"https://github.com/c.tar.gz"}]}""",
                ).build(),
            )
            server.start()
            val latest = runBlocking { PublicReleases(OkHttpClient(), server.url("/")).latest("coder/code-server") }
            assertEquals("/repos/coder/code-server/releases/latest", server.takeRequest().url.encodedPath)
            assertEquals("v4.139.0", latest.tag)
            assertEquals(digest.lowercase(), latest.assets[0].sha256)
            assertNull("only a SHA-256 digest counts", latest.assets[1].sha256)
            assertNull(latest.assets[2].sha256)
        }
    }
}
