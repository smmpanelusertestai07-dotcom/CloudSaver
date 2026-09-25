package com.pocketide.bridge

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.ConcurrentHashMap

/** Shows a web page to the owner. */
internal fun interface BrowserLauncher {
    suspend fun open(url: String)
}

/** The web addresses an agent may open on the phone: http and https, nothing else. */
internal object WebAddress {
    private const val MAX_LENGTH = 16 * 1024

    /** Characters java.net.URI refuses that browsers accept and percent-encode themselves. */
    private const val LOOSE = "\"<>^`{|}"
    private const val HEX = "0123456789ABCDEF"

    /**
     * Returns [url] ready to open, or throws IllegalArgumentException with the reason. Other
     * schemes (intent:, file:, content:, market:, javascript:) could reach other apps or this
     * app's files, so they are never opened. A user name in the address is refused too:
     * "https://bank.example@evil.example" reads as one site and opens another. So is a
     * backslash: browsers read it as "/", which would move where the host ends.
     *
     * Sign-in links open exactly as the agent printed them, except that characters a browser
     * would percent-encode anyway ("|", "{", non-ASCII letters) are encoded here first.
     */
    fun check(url: String): String {
        require(url.length in 1..MAX_LENGTH) { "The address is empty or too long." }
        require(url.none { it.isWhitespace() || it.isISOControl() }) { "The address has spaces or control characters in it." }
        require('\\' !in url) { "That is not a web address." }
        val encoded = encodeLoose(url)
        val uri = try {
            URI(encoded)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("That is not a web address.")
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "Only http and https addresses open on the phone." }
        val authority = uri.rawAuthority
        require(!authority.isNullOrEmpty() && !uri.isOpaque) { "The address has no host." }
        require('@' !in authority) { "Addresses with a user name or password are not opened." }
        return encoded
    }

    /** [url] with [LOOSE] and non-ASCII characters percent-encoded (as UTF-8); the rest as it is. */
    private fun encodeLoose(url: String): String {
        if (url.all { it.code < 0x80 && it !in LOOSE }) return url
        val out = StringBuilder(url.length + 16)
        var i = 0
        while (i < url.length) {
            val codePoint = url.codePointAt(i)
            val count = Character.charCount(codePoint)
            if (codePoint < 0x80 && url[i] !in LOOSE) {
                out.append(url[i])
            } else {
                for (byte in url.substring(i, i + count).toByteArray(Charsets.UTF_8)) {
                    val bits = byte.toInt() and 0xff
                    out.append('%').append(HEX[bits shr 4]).append(HEX[bits and 0xf])
                }
            }
            i += count
        }
        return out.toString()
    }
}

/**
 * The built-in "open_url" op: {"url": "https://..."} opens the page in the phone's browser.
 * One page per room every [minIntervalMs], so a looping script cannot flood the screen.
 */
internal class OpenUrlOp(
    private val browser: BrowserLauncher,
    private val minIntervalMs: Long = 2_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastOpened = ConcurrentHashMap<String, Long>()

    suspend operator fun invoke(agentId: String, args: JsonObject): JsonElement {
        val url = (args["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("open_url needs a \"url\" string.")
        val address = WebAddress.check(url)
        val now = clock()
        var allowed = false
        lastOpened.compute(agentId) { _, previous ->
            allowed = previous == null || now - previous >= minIntervalMs
            if (allowed) now else previous
        }
        check(allowed) { "A page was opened a moment ago. Wait a little before opening another." }
        browser.open(address)
        return buildJsonObject { put("opened", true) }
    }
}

/**
 * Opens pages with the phone's default browser, as a Custom Tab where the browser offers them:
 * a sign-in page then sits over PocketIDE, and closing it comes straight back. Android lets an
 * app start another app's screen only while it is on the screen itself, so the owner always
 * sees what opens and why.
 */
internal class AndroidBrowser(private val context: Context) : BrowserLauncher {
    override suspend fun open(url: String) = withContext(Dispatchers.Main) {
        check(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            "PocketIDE is not on the screen, so the page cannot open now. Open PocketIDE and ask again."
        }
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // The Custom Tabs protocol without its library: a session extra, even a null one,
            // asks for a tab. A browser without Custom Tabs ignores it and opens a normal page.
            .putExtras(Bundle().apply { putBinder(CUSTOM_TABS_SESSION, null) })
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("No browser on this phone can open that page.")
        }
    }

    private companion object {
        const val CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"
    }
}
