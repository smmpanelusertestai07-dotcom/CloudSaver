package com.pocketide.google

import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import com.pocketide.core.Clock
import com.pocketide.model.LinkHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Which Google account PocketIDE uses. An email is not a secret; tokens are never stored. */
internal interface AccountMemory {
    fun load(): String?
    fun save(email: String?)
}

internal class PrefsAccountMemory(private val prefs: SharedPreferences) : AccountMemory {
    override fun load(): String? = prefs.getString(KEY, null)

    override fun save(email: String?) {
        prefs.edit { if (email == null) remove(KEY) else putString(KEY, email) }
    }

    private companion object {
        const val KEY = "account"
    }
}

/**
 * Drive sign-in on top of [GoogleAuthorizer]. The account most recently authorized is the one
 * PocketIDE uses; a move to another account switches it when the new account is approved.
 * Tokens are kept in memory for a few minutes only, so a sync does not ask Play services for
 * every request; a token Drive rejects is dropped and cleared at Google.
 *
 * [whoAmI] asks Drive for the account's email with the given tokens (about.get fields=user).
 */
internal class GoogleDriveAuth(
    private val google: GoogleAuthorizer,
    private val memory: AccountMemory,
    private val clock: Clock,
    private val whoAmI: suspend (TokenSource) -> String,
) : DriveAuth {
    private val current = MutableStateFlow(memory.load())
    override val email: StateFlow<String?> = current.asStateFlow()

    private val cache = TokenCache(clock)
    private val fetching = Mutex()

    /** Tokens for whichever account is in use at the time of each request. */
    private val primaryTokens: TokenSource = object : TokenSource {
        override fun account(): String? = current.value
        override suspend fun token(): String = this@GoogleDriveAuth.token()
        override suspend fun rejected(token: String) = tokenRejected(token)
    }

    override suspend fun authorize(): DriveAuthResult = attempt {
        val known = current.value
        when (val grant = google.authorize(known, pickAccount = false)) {
            is Grant.Consent -> DriveAuthResult.NeedsConsent(grant.intent)
            is Grant.Token -> adopt(grant, known)
        }
    }

    override suspend fun completeConsent(data: Intent?): DriveAuthResult = attempt {
        when (val grant = google.fromIntent(data)) {
            is Grant.Consent -> DriveAuthResult.NeedsConsent(grant.intent)
            // The owner may have chosen any account in Google's sheet: always ask Drive which.
            is Grant.Token -> adopt(grant, known = null)
        }
    }

    override suspend fun authorizeNewAccount(): DriveAuthResult = attempt {
        when (val grant = google.authorize(account = null, pickAccount = true)) {
            is Grant.Consent -> DriveAuthResult.NeedsConsent(grant.intent)
            is Grant.Token -> adopt(grant, known = null)
        }
    }

    override suspend fun token(): String = tokenFor(current.value ?: throw DriveException.Revoked())

    /**
     * Silent only: when Google wants its sheet shown (access removed, or consent expired) this
     * throws [DriveException.Revoked] and the lock screen asks to reconnect. Background work
     * never shows UI and never loops on it.
     */
    override suspend fun tokenFor(email: String): String {
        cache.get(email)?.let { return it }
        return fetching.withLock {
            cache.get(email) ?: fetch(email)
        }
    }

    override suspend fun tokenRejected(token: String) {
        cache.drop(token)
        try {
            google.clearToken(token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: the next authorize asks Google again either way.
        }
    }

    override suspend fun health(): LinkHealth {
        if (current.value == null) return LinkHealth.NOT_CONNECTED
        return try {
            whoAmI(primaryTokens)
            LinkHealth.OK
        } catch (e: CancellationException) {
            throw e
        } catch (_: DriveException.Revoked) {
            LinkHealth.REVOKED
        } catch (_: DriveException.RateLimited) {
            LinkHealth.OK
        } catch (_: Exception) {
            // Unsure is never "revoked": only a clear answer from Google locks the app.
            LinkHealth.OFFLINE
        }
    }

    /**
     * Removes PocketIDE's access at Google, then forgets the account here. When Google cannot be
     * reached the local link is removed anyway; access can also be removed at
     * myaccount.google.com, Security, third-party connections.
     */
    override suspend fun disconnect() {
        val account = current.value
        cache.clear()
        if (account != null) {
            try {
                google.revoke(account)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // See above: the local state is cleared regardless.
            }
        }
        memory.save(null)
        current.value = null
    }

    private suspend fun fetch(account: String): String {
        val grant = try {
            google.authorize(account, pickAccount = false)
        } catch (e: AuthFailure) {
            throw when (e.kind) {
                AuthFailure.Kind.OFFLINE -> DriveException.Offline()
                AuthFailure.Kind.ACCOUNT -> DriveException.Revoked()
                else -> DriveException.Other(e.message ?: "Google could not give access right now. Try again later.")
            }
        }
        return when (grant) {
            is Grant.Consent -> throw DriveException.Revoked()
            is Grant.Token -> {
                if (!grant.coversDrive) throw DriveException.Revoked()
                cache.put(account, grant.value)
                grant.value
            }
        }
    }

    private suspend fun adopt(grant: Grant.Token, known: String?): DriveAuthResult {
        if (!grant.coversDrive) return DriveAuthResult.Failed(DRIVE_NOT_ALLOWED)
        val account = known ?: whoAmI(fixedTokens(grant.value))
        cache.put(account, grant.value)
        if (account != current.value) {
            memory.save(account)
            current.value = account
        }
        return DriveAuthResult.Authorized(account)
    }

    /** A token just handed over by Google; if Drive refuses it, there is no other to try. */
    private fun fixedTokens(token: String): TokenSource = object : TokenSource {
        override fun account(): String? = null
        override suspend fun token(): String = token
        override suspend fun rejected(token: String): Unit = throw DriveException.Revoked()
    }

    private suspend fun attempt(block: suspend () -> DriveAuthResult): DriveAuthResult {
        google.unavailable()?.let { return DriveAuthResult.Failed(it) }
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthFailure) {
            DriveAuthResult.Failed(e.message ?: GENERIC)
        } catch (_: DriveException.Offline) {
            DriveAuthResult.Failed("No connection to Google. Check the internet and try again.")
        } catch (_: DriveException.Revoked) {
            DriveAuthResult.Failed("Google Drive did not accept the new access. Try again.")
        } catch (_: DriveException.RateLimited) {
            DriveAuthResult.Failed("Google Drive is busy. Try again in a minute.")
        } catch (e: DriveException) {
            DriveAuthResult.Failed(e.message ?: GENERIC)
        }
    }

    private companion object {
        const val GENERIC = "Google Drive could not be connected. Try again in a minute."
        const val DRIVE_NOT_ALLOWED =
            "Google Drive access was not allowed. Tick the box for PocketIDE's own data in Google's window, then try again."
    }
}

/** Access tokens by account, kept briefly in memory (Google's last about an hour). */
internal class TokenCache(private val clock: Clock, private val keepMs: Long = KEEP_MS) {
    private data class Held(val token: String, val at: Long)

    private val held = HashMap<String, Held>()

    @Synchronized
    fun get(account: String): String? {
        val found = held[account] ?: return null
        if (clock.now() - found.at >= keepMs) {
            held.remove(account)
            return null
        }
        return found.token
    }

    @Synchronized
    fun put(account: String, token: String) {
        held[account] = Held(token, clock.now())
    }

    @Synchronized
    fun drop(token: String) {
        held.values.removeAll { it.token == token }
    }

    @Synchronized
    fun clear() = held.clear()

    private companion object {
        const val KEEP_MS = 10L * 60 * 1000
    }
}
