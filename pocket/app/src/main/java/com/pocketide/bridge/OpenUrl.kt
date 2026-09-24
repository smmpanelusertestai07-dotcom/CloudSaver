package com.pocketide.bridge

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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

    /**
     * Returns [url] when the browser may open it; otherwise throws IllegalArgumentException with
     * the reason. Other schemes (intent:, file:, content:, market:, javascript:) could reach
     * other apps or this app's files, so they are never opened. A user name in the address is
     * refused too: "https://bank.example@evil.example" reads as one site and opens another.
     */
    fun check(url: String): String {
        require(url.length in 1..MAX_LENGTH) { "The address is empty or too long." }
        require(url.none { it.isWhitespace() || it.isISOControl() }) { "The address has spaces or control characters in it." }
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("That is not a web address.")
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "Only http and https addresses open on the phone." }
        val authority = uri.rawAuthority
        require(!authority.isNullOrEmpty() && !uri.isOpaque) { "The address has no host." }
        require('@' !in authority) { "Addresses with a user name or password are not opened." }
        return url
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
 * Opens pages with the phone's default browser. Android lets an app start another app's screen
 * only while it is on the screen itself, so the owner always sees what opens and why.
 */
internal class AndroidBrowser(private val context: Context) : BrowserLauncher {
    override suspend fun open(url: String) = withContext(Dispatchers.Main) {
        check(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            "PocketIDE is not on the screen, so the page cannot open now. Open PocketIDE and ask again."
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("No browser on this phone can open that page.")
        }
    }
}
