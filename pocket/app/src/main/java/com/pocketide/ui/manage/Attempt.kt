package com.pocketide.ui.manage

import com.pocketide.core.Redact
import com.pocketide.github.NotConnectedException
import com.pocketide.google.DriveException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Runs one owner-triggered call. Cancellation (leaving the screen) is passed on; any other
 * failure becomes a value, so a module error never crashes a screen.
 */
suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/** One short sentence the owner can act on, never a stack trace or a token. */
object PlainError {
    private const val MAX_LENGTH = 160
    const val GENERIC = "That did not work. Try again in a moment."

    fun of(error: Throwable): String = when (error) {
        is NotConnectedException -> "GitHub is not connected. Reconnect it in Settings."
        is DriveException.StorageFull -> "Google storage is full. Free some space at one.google.com/storage."
        is DriveException.Offline -> "No connection. Try again when you are online."
        is DriveException.Revoked -> "Google Drive access was removed. Reconnect it in Settings."
        is DriveException.RateLimited -> "Google Drive asked us to slow down. Try again in a minute."
        is IOException -> "No connection. Try again when you are online."
        else -> readable(error.message) ?: GENERIC
    }

    /** A module's own message is shown only when it reads like a sentence for people. */
    internal fun readable(message: String?): String? {
        val text = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.length > MAX_LENGTH || text.contains('\n')) return null
        if (!text.first().isUpperCase() || !text.contains(' ')) return null
        if (looksTechnical(text)) return null
        val clean = Redact.text(text)
        return if (clean.endsWith('.') || clean.endsWith('?') || clean.endsWith('!')) clean else "$clean."
    }

    private fun looksTechnical(text: String): Boolean =
        listOf("Exception", "exception", "java.", "kotlin.", "http://", "https://", "null", "stub", "HTTP ")
            .any { text.contains(it) }
}
