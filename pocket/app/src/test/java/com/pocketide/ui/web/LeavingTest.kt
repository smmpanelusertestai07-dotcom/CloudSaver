package com.pocketide.ui.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an agent page hands to Chrome when it leaves the app. */
class LeavingTest {
    private val bridge = "http://127.0.0.1:41234"
    private val opened = mutableListOf<String>()
    private val holder = WebViewHolder().apply {
        callbacks.isInternal = { url -> url.startsWith("$bridge/") }
        callbacks.openExternal = { url -> opened += url }
    }

    @Test
    fun codeServersAddressForAPortOpensThePortInChrome() {
        // On the bridge's own origin it would count as internal, and nothing would open at all.
        holder.leave("$bridge/proxy/1455/auth/callback?code=x#s", userGesture = true)
        assertEquals(listOf("http://localhost:1455/auth/callback?code=x#s"), opened)
    }

    @Test
    fun withoutATapTheQuestionNamesThePortItself() {
        holder.leave("$bridge/proxy/1455/", userGesture = false)
        assertTrue(opened.isEmpty())
        assertEquals("http://localhost:1455/", holder.askToOpen)
    }

    @Test
    fun theBridgesOwnPagesStayInside() {
        holder.leave("$bridge/?folder=%2Fwork", userGesture = true)
        assertTrue(opened.isEmpty())
    }
}
