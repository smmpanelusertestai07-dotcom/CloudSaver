package com.pocketide.bridge

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenUrlOpTest {
    private val opened = mutableListOf<String>()
    private var now = 1_000_000L
    private val op = OpenUrlOp(browser = { opened += it }, minIntervalMs = 2_000, clock = { now })

    private fun open(agent: String, url: String) = runBlocking { op(agent, buildJsonObject { put("url", url) }) }

    @Test fun `http and https pages open in the browser`() {
        val result = open("claude", "https://accounts.google.com/o/oauth2/auth?client_id=1&redirect_uri=http%3A%2F%2Flocalhost%3A40075%2Fauth%2Fcallback")
        assertEquals(buildJsonObject { put("opened", true) }, result)
        now += 2_000
        open("claude", "http://localhost:1455/auth/callback?code=x")
        assertEquals(2, opened.size)
        assertTrue(opened[0].startsWith("https://accounts.google.com/"))
    }

    @Test fun `long sign-in links open whole, with only what a browser would encode encoded`() {
        val challenge = "a".repeat(43)
        val longLink = "https://accounts.google.com/o/oauth2/v2/auth?access_type=offline&client_id=${"1".repeat(300)}" +
            "&code_challenge=$challenge&code_challenge_method=S256&redirect_uri=http%3A%2F%2Flocalhost%3A40075%2Fauth%2Fcallback" +
            "&response_type=code&scope=${"openid%20email%20profile%20".repeat(20)}&state=${"b".repeat(64)}"
        assertTrue(longLink.length > 700)
        assertEquals(longLink, WebAddress.check(longLink))

        assertEquals("https://example.com/a%7Cb?q=%7B1%7D&x=%5E", WebAddress.check("https://example.com/a|b?q={1}&x=^"))
        assertEquals("https://example.com/caf%C3%A9#%22", WebAddress.check("https://example.com/café#\""))
        assertEquals("https://example.com/%F0%9F%99%82", WebAddress.check("https://example.com/🙂"))
    }

    @Test fun `other schemes and deceptive addresses are never opened`() {
        val refused = listOf(
            "https://evil.example\\@bank.example/", "https://evil.example\\bank.example",
            "javascript:alert(1)", "intent://scan/#Intent;scheme=zxing;end", "file:///data/data/com.pocketide/files",
            "content://com.pocketide.files/x", "market://details?id=x", "data:text/html,hi", "ftp://example.com/",
            "https://bank.example@evil.example/", "https://user:pass@example.com/", "https:///no-host", "https:opaque",
            " https://example.com", "https://exa mple.com/", "https://example.com/\nX", "", "x".repeat(20_000),
        )
        for (url in refused) {
            now += 10_000
            assertThrows<IllegalArgumentException> { open("claude", url) }
        }
        assertTrue(opened.isEmpty())
    }

    @Test fun `the url must be a string`() {
        assertThrows<IllegalArgumentException> { runBlocking { op("claude", JsonObject(emptyMap())) } }
        assertThrows<IllegalArgumentException> { runBlocking { op("claude", JsonObject(mapOf("url" to JsonPrimitive(5)))) } }
        assertTrue(opened.isEmpty())
    }

    @Test fun `each room may open one page every two seconds`() {
        open("claude", "https://example.com/1")
        now += 500
        assertThrows<IllegalStateException> { open("claude", "https://example.com/2") }
        open("codex", "https://example.com/3")
        now += 1_500
        open("claude", "https://example.com/4")
        assertEquals(listOf("https://example.com/1", "https://example.com/3", "https://example.com/4"), opened)
    }
}
