package com.pocketide.google

import android.content.Context
import com.pocketide.AppGraph
import com.pocketide.core.Clock
import com.pocketide.core.Http
import java.util.concurrent.ConcurrentHashMap

fun createDriveAuth(graph: AppGraph): DriveAuth {
    val http = DriveHttp(Http.client)
    return GoogleDriveAuth(
        google = PlayAuthorizer(graph.context),
        memory = PrefsAccountMemory(graph.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)),
        clock = graph.clock,
        whoAmI = { tokens -> accountEmail(http, tokens) },
    )
}

fun createDriveStore(graph: AppGraph): DriveStore =
    DriveStores(DriveHttp(Http.client), graph.driveAuth, graph.clock).primary

/**
 * The store for the account in use, and one per other account the owner authorized (a move
 * copies between two). Stores are reused, so a cut-off upload resumes on the next try.
 */
internal class DriveStores(private val http: DriveHttp, private val auth: DriveAuth, private val clock: Clock) {
    private val byAccount = ConcurrentHashMap<String, DriveStore>()

    val primary: DriveStore = DriveRestStore(http, PrimaryTokens(auth), clock, ::of)

    fun of(email: String): DriveStore =
        byAccount.getOrPut(email) { DriveRestStore(http, AccountTokens(auth, email), clock, ::of) }

    private class PrimaryTokens(private val auth: DriveAuth) : TokenSource {
        override fun account(): String? = auth.email.value
        override suspend fun token(): String = auth.token()
        override suspend fun rejected(token: String) = auth.tokenRejected(token)
    }

    private class AccountTokens(private val auth: DriveAuth, private val email: String) : TokenSource {
        override fun account(): String = email
        override suspend fun token(): String = auth.tokenFor(email)
        override suspend fun rejected(token: String) = auth.tokenRejected(token)
    }
}

private const val PREFS = "pocketide.google"
