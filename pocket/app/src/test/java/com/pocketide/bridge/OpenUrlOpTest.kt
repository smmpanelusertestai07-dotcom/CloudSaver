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

    @Test fun `other schemes and deceptive addresses are never opened`() {
        val refused = listOf(
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
