package com.pocketide.google

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/** What Google answered to an authorization request. */
internal sealed interface Grant {
    /** [coversDrive] is false when the owner unticked the Drive permission in Google's sheet. */
    data class Token(val value: String, val coversDrive: Boolean = true) : Grant

    /** Google's own sheet must be shown first. */
    data class Consent(val intent: PendingIntent) : Grant
}

/** Why Google gave no answer; [message] is a plain sentence for the owner. */
internal class AuthFailure(val kind: Kind, message: String) : Exception(message) {
    enum class Kind {
        OFFLINE,
        /** The account needs signing in again, or is no longer on the phone. */
        ACCOUNT,
        CANCELLED,

        /** Google does not know this build (DEVELOPER_ERROR): the owner's Google Cloud set-up is the fix. */
        UNKNOWN_BUILD,
        OTHER,
    }
}

/** The Play services side of Drive sign-in, behind an interface so the rules around it can be tested. */
internal interface GoogleAuthorizer {
    /** Null when Play services can be used, else why not. */
    fun unavailable(): String?

    /** Silent when access was granted before; [pickAccount] always shows Google's account chooser. */
    suspend fun authorize(account: String?, pickAccount: Boolean): Grant

    /** The result of Google's sheet, from the activity result's data. */
    fun fromIntent(data: Intent?): Grant

    suspend fun clearToken(token: String)

    /** Removes PocketIDE's access to [account] at Google (all scopes). */
    suspend fun revoke(account: String)
}

/**
 * Play services' AuthorizationClient, asking for `drive.appdata` only. Access tokens only: no
 * offline access, so no server client ID and no refresh token on the phone. Google Sign-In is
 * not used (play-services-auth 22 removed it).
 */
internal class PlayAuthorizer(private val context: Context) : GoogleAuthorizer {
    private val client: AuthorizationClient by lazy { Identity.getAuthorizationClient(context) }

    override fun unavailable(): String? =
        when (val code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)) {
            ConnectionResult.SUCCESS -> null
            ConnectionResult.SERVICE_MISSING, ConnectionResult.SERVICE_INVALID ->
                "This phone does not have Google Play services, which PocketIDE needs to connect Google Drive."
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED, ConnectionResult.SERVICE_UPDATING ->
                "Update Google Play services in the Play Store, then try again."
            ConnectionResult.SERVICE_DISABLED ->
                "Google Play services is turned off. Turn it on in Android Settings, Apps, then try again."
            else -> "Google Play services is not working on this phone (code $code). Restart the phone and try again."
        }

    override suspend fun authorize(account: String?, pickAccount: Boolean): Grant = mapped {
        client.authorize(request(account, pickAccount)).await().toGrant()
    }

    override fun fromIntent(data: Intent?): Grant {
        if (data == null) throw AuthFailure(AuthFailure.Kind.CANCELLED, CANCELLED)
        return try {
            client.getAuthorizationResultFromIntent(data).toGrant()
        } catch (e: ApiException) {
            throw failure(e)
        }
    }

    override suspend fun clearToken(token: String) {
        mapped { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    override suspend fun revoke(account: String) {
        val request = RevokeAccessRequest.builder()
            .setAccount(Account(account, ACCOUNT_TYPE))
            .setScopes(listOf(Scope(DRIVE_APPDATA)))
            .build()
        mapped { client.revokeAccess(request).await() }
    }

    private fun request(account: String?, pickAccount: Boolean): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA)))
            .apply { if (account != null) setAccount(Account(account, ACCOUNT_TYPE)) }
            .apply { if (pickAccount) setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT) }
            .build()

    private suspend fun <T> mapped(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiException) {
        throw failure(e)
    }

    private fun AuthorizationResult.toGrant(): Grant {
        if (hasResolution()) {
            val intent = pendingIntent ?: throw AuthFailure(AuthFailure.Kind.OTHER, NO_ANSWER)
            return Grant.Consent(intent)
        }
        val token = accessToken ?: throw AuthFailure(AuthFailure.Kind.OTHER, NO_ANSWER)
        val scopes = grantedScopes
        return Grant.Token(token, coversDrive = scopes.isEmpty() || DRIVE_APPDATA in scopes)
    }

    private fun failure(e: ApiException): AuthFailure = when (e.statusCode) {
        CommonStatusCodes.NETWORK_ERROR, CommonStatusCodes.TIMEOUT, CommonStatusCodes.INTERRUPTED ->
            AuthFailure(AuthFailure.Kind.OFFLINE, "No connection to Google. Check the internet and try again.")
        CommonStatusCodes.CANCELED -> AuthFailure(AuthFailure.Kind.CANCELLED, CANCELLED)
        CommonStatusCodes.SIGN_IN_REQUIRED, CommonStatusCodes.INVALID_ACCOUNT ->
            AuthFailure(AuthFailure.Kind.ACCOUNT, "That Google account needs signing in again on this phone. Check it in Android Settings, Accounts.")
        // The owner's Google Cloud project has no Android client for this package and signing key.
        CommonStatusCodes.DEVELOPER_ERROR ->
            AuthFailure(AuthFailure.Kind.UNKNOWN_BUILD, UnknownBuild.message(context.packageName, UnknownBuild.signingSha1(context)))
        else -> AuthFailure(AuthFailure.Kind.OTHER, "Google could not give access right now (code ${e.statusCode}). Try again in a minute.")
    }

    private companion object {
        const val ACCOUNT_TYPE = "com.google"
        const val CANCELLED = "Google's window was closed before you allowed access. Nothing changed."
        const val NO_ANSWER = "Google gave no answer. Try again."
    }
}

/** The one scope PocketIDE asks for: its own hidden folder in Drive, nothing else. */
internal const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
