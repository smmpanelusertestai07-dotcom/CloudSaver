package com.pocketide.ui.web

import com.pocketide.ui.screens.computer.acceptsOnlyImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPolicyTest {
    @Test
    fun `GitHub's sign-in and the computer stay in the app`() {
        listOf(
            "https://github.com/login?return_to=%2Fcodespaces",
            "https://github.com/sessions/two-factor",
            "https://fuzzy-space-guide-1234.github.dev/",
            "https://fuzzy-space-guide-1234-9000.app.github.dev/",
        ).forEach { assertEquals(it, Navigation.STAY, WebPolicy.navigation(it)) }
    }

    @Test
    fun `every other company's page opens in Chrome`() {
        listOf(
            "https://accounts.google.com/o/oauth2/v2/auth?client_id=x",
            "https://claude.ai/oauth/authorize",
            "https://auth.openai.com/authorize",
            "https://github.dev/",
            "https://evilgithub.dev/",
            "https://github.com.evil.example/login",
        ).forEach { assertEquals(it, Navigation.CHROME, WebPolicy.navigation(it)) }
    }

    @Test
    fun `anything that is not https goes nowhere`() {
        listOf(
            "http://github.com/login",
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "file:///data/data/com.pocketide/shared_prefs",
            "vscode://anthropic.claude-code/callback",
            "not a url",
        ).forEach { assertEquals(it, Navigation.BLOCK, WebPolicy.navigation(it)) }
    }

    @Test
    fun `the page starts only on a codespace's own address`() {
        assertTrue(WebPolicy.isComputerPage("https://fuzzy-space-guide-1234.github.dev"))
        assertFalse(WebPolicy.isComputerPage("https://fuzzy-space-guide-1234-9000.app.github.dev"))
        assertFalse(WebPolicy.isComputerPage("http://fuzzy-space-guide-1234.github.dev"))
        assertFalse(WebPolicy.isComputerPage("https://github.com/codespaces"))
    }

    @Test
    fun `a picture-only file input gets the photo picker`() {
        assertTrue(acceptsOnlyImages(listOf("image/png,image/jpeg")))
        assertFalse(acceptsOnlyImages(listOf("image/png", ".pdf")))
        assertFalse(acceptsOnlyImages(emptyList()))
        assertFalse(acceptsOnlyImages(listOf("")))
    }
}
