package com.pocketide.ui.web

import java.net.URI
import java.util.Locale

/** Where a page in the computer screen wants to go, and what the app does about it. */
enum class Navigation {
    /** GitHub's own sign-in and the cloud computer itself: stays in the app. */
    STAY,

    /** Any other web address, sign-ins to Google, Anthropic and OpenAI among them: Chrome. */
    CHROME,

    /** Not a web address (intent:, file:, javascript:, plain http): goes nowhere. */
    BLOCK,
}

/**
 * URL rules for the computer screen. Pure Kotlin (java.net.URI), so they are tested on the JVM;
 * the WebView's clients call them.
 *
 * Only GitHub stays inside the app. Google refuses sign-in inside embedded web views, and a
 * browser the owner already trusts is the right place for every other company's sign-in page,
 * so everything else opens in Chrome (a Custom Tab), with its own cookies and password manager.
 */
object WebPolicy {
    /** Hosts the computer screen may show: github.com for sign-in, the computer on github.dev. */
    private val GITHUB_HOSTS = setOf("github.com", "www.github.com")

    /** VS Code for the web runs on `<codespace>.github.dev`; forwarded ports on `<codespace>-<port>.app.github.dev`. */
    private const val CODESPACES_SUFFIX = ".github.dev"

    fun navigation(url: String): Navigation {
        val uri = parse(url) ?: return Navigation.BLOCK
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return Navigation.BLOCK
        return when {
            scheme != "https" -> Navigation.BLOCK
            host in GITHUB_HOSTS -> Navigation.STAY
            host.endsWith(CODESPACES_SUFFIX) && host.length > CODESPACES_SUFFIX.length -> Navigation.STAY
            else -> Navigation.CHROME
        }
    }

    /** True for an https address, the only kind the app hands to Chrome. */
    fun isWebLink(url: String): Boolean = parse(url)?.scheme?.lowercase(Locale.ROOT) == "https" && parse(url)?.host != null

    /** The host an "open in Chrome" note names, so the owner sees where it goes. */
    fun hostOf(url: String): String? = parse(url)?.host?.lowercase(Locale.ROOT)

    /** The computer's own address: the only page the WebView starts on. */
    fun isComputerPage(url: String): Boolean {
        val uri = parse(url) ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && host.endsWith(CODESPACES_SUFFIX) && !host.endsWith(".app$CODESPACES_SUFFIX")
    }

    private fun parse(url: String): URI? = try {
        URI(url.trim())
    } catch (_: Exception) {
        null
    }
}
