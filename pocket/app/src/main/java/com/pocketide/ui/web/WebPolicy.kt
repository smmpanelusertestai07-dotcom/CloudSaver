package com.pocketide.ui.web

import java.net.URI

/** The photo picker mode an agent page's file input asks for. */
enum class PickerKind { IMAGES, VIDEOS, IMAGES_AND_VIDEOS }

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

    private val IMAGE_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".heic", ".heif", ".bmp")
    private val VIDEO_EXTENSIONS = setOf(".mp4", ".webm", ".mov", ".3gp", ".mkv")

    private fun parse(url: String): URI? = try {
        URI(url.trim())
    } catch (_: Exception) {
        null
    }
}
