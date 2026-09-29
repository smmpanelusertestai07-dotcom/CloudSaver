package com.pocketide.ui.web

import com.pocketide.ui.screens.workspace.acceptsOnlyImages
import com.pocketide.ui.screens.workspace.pasteLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPolicyTest {
    private val port = 41234

    @Test
    fun `only this phone's code-server stays in the app`() {
        listOf(
            "http://127.0.0.1:$port/?folder=/root/projects",
            "http://127.0.0.1:$port/stable-abc/static/out/vs/code/browser/workbench/workbench.html",
        ).forEach { assertEquals(it, Navigation.STAY, WebPolicy.navigation(it, port)) }
        assertTrue(WebPolicy.isIdePage("http://127.0.0.1:$port/", port))
        assertFalse(WebPolicy.isIdePage("http://127.0.0.1:${port + 1}/", port))
        assertFalse(WebPolicy.isIdePage("http://localhost:$port/", port))
    }

    @Test
    fun `every company's page, and this phone's other servers, open in Chrome`() {
        listOf(
            "https://accounts.google.com/o/oauth2/v2/auth?client_id=x",
            "https://claude.com/cai/oauth/authorize",
            "https://auth.openai.com/authorize",
            "https://github.com/login/device",
            "http://localhost:1455/auth/callback?code=x",
            "http://127.0.0.1:${port + 1}/",
            "http://5173.localhost:40000/",
        ).forEach { assertEquals(it, Navigation.CHROME, WebPolicy.navigation(it, port)) }
        // With code-server stopped, its old address is only another local page.
        assertEquals(Navigation.CHROME, WebPolicy.navigation("http://127.0.0.1:$port/", null))
    }

    @Test
    fun `plain http to another computer, and anything that is not the web, goes nowhere`() {
        listOf(
            "http://github.com/login",
            "http://evil.example/",
            "http://localhost.evil.example/",
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "file:///data/data/com.pocketide/shared_prefs",
            "code-oss://google.google-antigravity/auth",
            "not a url",
        ).forEach { assertEquals(it, Navigation.BLOCK, WebPolicy.navigation(it, port)) }
    }

    @Test
    fun `the agents' sign-in pages open without a question, look-alikes do not`() {
        val sites = listOf("https://accounts.google.com", "https://*.openai.com", "https://claude.ai")
        listOf(
            "https://accounts.google.com/o/oauth2/auth?client_id=x",
            "https://auth.openai.com/oauth/authorize",
            "https://claude.ai/oauth/authorize",
        ).forEach { assertTrue(it, WebPolicy.isSignInSite(it, sites)) }
        listOf(
            "http://accounts.google.com/",
            "https://accounts.google.com.evil.example/",
            "https://evilopenai.com/",
            "https://openai.com/",
            "https://claude.ai.example/",
            "not a url",
        ).forEach { assertFalse(it, WebPolicy.isSignInSite(it, sites)) }
    }

    @Test
    fun `a picture-only file input gets the photo picker`() {
        assertTrue(acceptsOnlyImages(listOf("image/png,image/jpeg")))
        assertFalse(acceptsOnlyImages(listOf("image/png", ".pdf")))
        assertFalse(acceptsOnlyImages(emptyList()))
        assertFalse(acceptsOnlyImages(listOf("")))
    }

    @Test
    fun `Paste types one line into the terminal, and never runs anything`() {
        assertEquals("4/0AVGzR1A-code", pasteLine("4/0AVGzR1A-code"))
        // Copied with its line break at the end, as a page often gives it.
        assertEquals("git status", pasteLine("git status\r\n"))
        // A line break inside would press Enter halfway through.
        assertNull(pasteLine("echo one\nrm -rf ~/projects"))
        assertNull(pasteLine("one\rtwo"))
        assertNull(pasteLine(null))
        assertNull(pasteLine(""))
        assertNull(pasteLine("  \n"))
        assertNull(pasteLine("x".repeat(2001)))
    }
}
