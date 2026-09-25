package com.pocketide.ui.web

import com.pocketide.media.KeyFrames
import java.net.URI

/** The photo picker mode an agent page's file input asks for. */
enum class PickerKind { IMAGES, VIDEOS, IMAGES_AND_VIDEOS }

/** The picker to open for a file input; [framesPerVideo] is 0 when videos go to the page as they are. */
data class FilePick(val picker: PickerKind, val framesPerVideo: Int)

enum class ExternalOpen { OPEN, ASK, IGNORE }

/**
 * URL rules shared by the app's WebViews. Pure Kotlin (java.net.URI), so they are tested on the
 * JVM; the WebViews call them from their clients.
 */
object WebPolicy {
    /** "scheme://host[:port]" of an http(s) URL, or null for anything else. */
    fun originOf(url: String): String? {
        val uri = parse(url) ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        return if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
    }

    /** Only ordinary web links go to Chrome; intent:, file:, javascript: and the rest go nowhere. */
    fun isWebLink(url: String): Boolean = originOf(url) != null

    /**
     * What happens to a page's attempt to leave the app for [url]. A tap opens Chrome at once
     * (sign-in pages must open without extra steps); a page acting on its own only gets to ask,
     * so a script cannot throw the owner into Chrome again and again.
     */
    fun externalOpen(url: String, userGesture: Boolean): ExternalOpen = when {
        !isWebLink(url) -> ExternalOpen.IGNORE
        userGesture -> ExternalOpen.OPEN
        else -> ExternalOpen.ASK
    }

    /**
     * code-server's address for a port on this phone, "http://127.0.0.1:<any>/proxy/<n>/<rest>"
     * (or localhost), as VS Code's asExternalUri makes it, turned into "http://localhost:<n>/<rest>"
     * with its query and fragment. The rooms run code-server with --disable-proxy, so that route
     * does not exist, while Chrome reaches the port itself. Any other address comes back as it was.
     */
    fun withoutEngineProxy(url: String): String {
        val uri = parse(url)?.takeIf { it.scheme.equals("http", ignoreCase = true) && it.rawUserInfo == null } ?: return url
        if (uri.host?.lowercase() !in LOOPBACK_NAMES) return url
        val proxied = ENGINE_PROXY.matchEntire(uri.rawPath.orEmpty()) ?: return url
        val port = proxied.groupValues[1].toInt().takeIf { it in 1..MAX_PORT } ?: return url
        return buildString {
            append("http://localhost:").append(port).append('/').append(proxied.groupValues[2])
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }

    /** The host an "open in Chrome?" question names, so the owner sees where it goes. */
    fun hostOf(url: String): String? = parse(url)?.host?.lowercase()

    /** Documents a frame creates for itself (srcdoc, a blank frame) carry no address of their own. */
    fun isFrameLocal(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower == "about:blank" || lower == "about:srcdoc"
    }

    /**
     * The safe file viewer loads one document from a string; every other request (images,
     * scripts, frames, links) is refused.
     */
    fun safeViewerAllows(url: String, mainFrame: Boolean): Boolean {
        if (!mainFrame) return false
        val scheme = parse(url)?.scheme?.lowercase() ?: return url.trim().lowercase().startsWith("data:")
        return scheme == "data" || scheme == "about"
    }

    /** Which picker a file input's accept list needs. Non-media types fall back to both. */
    fun pickerKind(acceptTypes: List<String>): PickerKind {
        val types = acceptTypes.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (types.isEmpty()) return PickerKind.IMAGES_AND_VIDEOS
        val images = types.all { it.startsWith("image/") || it in IMAGE_EXTENSIONS }
        val videos = types.all { it.startsWith("video/") || it in VIDEO_EXTENSIONS }
        return when {
            images -> PickerKind.IMAGES
            videos -> PickerKind.VIDEOS
            else -> PickerKind.IMAGES_AND_VIDEOS
        }
    }

    /**
     * How a file input is served (§8): an input that takes only pictures still offers videos,
     * and each picked video reaches the page as [FilePick.framesPerVideo] key frames.
     */
    fun filePick(acceptTypes: List<String>, multiple: Boolean): FilePick =
        when (val accepts = pickerKind(acceptTypes)) {
            PickerKind.IMAGES -> FilePick(PickerKind.IMAGES_AND_VIDEOS, KeyFrames.perVideo(multiple))
            else -> FilePick(accepts, framesPerVideo = 0)
        }

    /** What the owner is told when picked videos gave no frame to send, or null when all did. */
    fun unreadableVideos(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "The video could not be read, so it was not sent."
        else -> "$count videos could not be read, so they were not sent."
    }

    private const val MAX_PORT = 65535
    private val LOOPBACK_NAMES = setOf("127.0.0.1", "localhost")

    /** "/proxy/<n>" alone or followed by "/<rest>"; n is one to five ASCII digits. */
    private val ENGINE_PROXY = Regex("/proxy/([0-9]{1,5})(?:/(.*))?", RegexOption.DOT_MATCHES_ALL)

    private val IMAGE_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".heic", ".heif", ".bmp")
    private val VIDEO_EXTENSIONS = setOf(".mp4", ".webm", ".mov", ".3gp", ".mkv")

    private fun parse(url: String): URI? = try {
        URI(url.trim())
    } catch (_: Exception) {
        null
    }
}
