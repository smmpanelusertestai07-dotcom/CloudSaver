package com.pocketide.google

import android.app.PendingIntent
import com.pocketide.model.LinkHealth
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream

sealed interface DriveAuthResult {
    data class Authorized(val email: String?) : DriveAuthResult
    /** The owner must approve in Google's own sheet; launch [intent] and call [DriveAuth.completeConsent]. */
    data class NeedsConsent(val intent: PendingIntent) : DriveAuthResult
    data class Failed(val why: String) : DriveAuthResult
}

/**
 * Google sign-in for Drive through Play services' AuthorizationClient, scope `drive.appdata`
 * only. No client secret in the app; the Android OAuth client is matched by package name and
 * signing certificate.
 */
interface DriveAuth {
    val email: StateFlow<String?>

    /** Asks for access; silent when already granted. */
    suspend fun authorize(): DriveAuthResult

    /** Finishes a consent the owner approved (the result data of [DriveAuthResult.NeedsConsent]). */
    suspend fun completeConsent(data: android.content.Intent?): DriveAuthResult

    /** A fresh access token (works from background work when access was granted before). */
    suspend fun token(): String

    suspend fun health(): LinkHealth

    suspend fun disconnect()

    /** Lets the owner pick another Google account ("Move to another Google account"). */
    suspend fun authorizeNewAccount(): DriveAuthResult

    /** A token for a specific account the owner authorized (the move source or target). */
    suspend fun tokenFor(email: String): String

    /** Drive refused [token] (HTTP 401): forget it, so the next [token] or [tokenFor] is fresh. */
    suspend fun tokenRejected(token: String) = Unit
}

data class DriveFile(val id: String, val name: String, val size: Long, val modifiedTime: String?, val md5: String?)

data class DriveQuota(
    /** Total Google storage; null for unlimited. */
    val limitBytes: Long?,
    /** All Google storage used (Gmail, Photos, Drive). */
    val usageBytes: Long,
    val usageInDriveBytes: Long,
    /** PocketIDE's hidden folder, summed from its files. */
    val appDataBytes: Long,
    val email: String?,
)

/** Errors the sync engine reacts to differently. */
sealed class DriveException(message: String) : Exception(message) {
    class StorageFull : DriveException("Google storage is full")
    class RateLimited(val retryAfterMs: Long) : DriveException("Drive asked us to slow down")
    class Revoked : DriveException("Drive access was removed")
    class Offline : DriveException("No connection")
    open class Other(message: String) : DriveException(message)

    /** The file is not in Drive (any more). Still an [Other], so older callers treat it the same. */
    class NotFound : Other("That file is no longer in Google Drive")
}

/** The Drive hidden app folder (`appDataFolder`). Everything written here is already encrypted. */
interface DriveStore {
    suspend fun list(): List<DriveFile>
    suspend fun find(name: String): DriveFile?
    /** Creates, or replaces the content of [existingId]. Resumable for large files. */
    suspend fun upload(name: String, source: File, existingId: String? = null): DriveFile
    suspend fun uploadBytes(name: String, bytes: ByteArray, existingId: String? = null): DriveFile
    suspend fun download(id: String, sink: OutputStream)
    suspend fun open(id: String): InputStream
    /** Permanent delete (not Drive's Trash). */
    suspend fun delete(id: String)
    suspend fun quota(): DriveQuota

    /** The same store, acting as another authorized account (used only by the move). */
    fun withAccount(email: String): DriveStore
}
