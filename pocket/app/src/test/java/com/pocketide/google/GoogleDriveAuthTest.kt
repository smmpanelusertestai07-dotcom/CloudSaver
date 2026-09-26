package com.pocketide.google

import android.app.PendingIntent
import android.content.Intent
import com.pocketide.model.LinkHealth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GoogleDriveAuthTest {
    private val google = FakeGoogle()
    private val memory = FakeMemory()
    private val clock = TestClock()
    private val lookups = mutableListOf<String>()
    private var whoAmIResult: () -> String = { "owner@example.com" }

    private fun auth() = GoogleDriveAuth(google, memory, clock) { tokens ->
        lookups += tokens.token()
        whoAmIResult()
    }

    @Test
    fun `first sign-in asks Drive which account it is and remembers it`() = runBlocking<Unit> {
        google.grants += Grant.Token("ya29.first")
        val auth = auth()

        val result = auth.authorize()

        assertEquals(DriveAuthResult.Authorized("owner@example.com"), result)
        assertEquals("owner@example.com", auth.email.value)
        assertEquals("owner@example.com", memory.saved)
        assertEquals(listOf("ya29.first"), lookups)
        assertEquals(listOf(null to false), google.requests)
    }

    @Test
    fun `a known account is asked for by name and not looked up again`() = runBlocking<Unit> {
        memory.saved = "owner@example.com"
        google.grants += Grant.Token("ya29.again")
        val auth = auth()

        assertEquals(DriveAuthResult.Authorized("owner@example.com"), auth.authorize())
        assertEquals(listOf("owner@example.com" to false), google.requests)
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun `Google's sheet is handed to the screen`() = runBlocking<Unit> {
        val intent = pendingIntent()
        google.grants += Grant.Consent(intent)

        val result = auth().authorize()

        assertSame(intent, (result as DriveAuthResult.NeedsConsent).intent)
    }

    @Test
    fun `missing Play services is a plain failure`() = runBlocking<Unit> {
        google.unavailable = "Update Google Play services in the Play Store, then try again."
        val result = auth().authorize()
        assertEquals(DriveAuthResult.Failed("Update Google Play services in the Play Store, then try again."), result)
        assertTrue(google.requests.isEmpty())
    }

    @Test
    fun `a build Google does not know says so, so the screen can offer the set-up steps`() = runBlocking<Unit> {
        google.grants += AuthFailure(AuthFailure.Kind.UNKNOWN_BUILD, "Google does not know this build of PocketIDE.")
        google.grants += AuthFailure(AuthFailure.Kind.OTHER, "Google could not give access right now (code 8). Try again in a minute.")
        val auth = auth()

        assertEquals(DriveAuthResult.Failed("Google does not know this build of PocketIDE.", unknownBuild = true), auth.authorize())
        assertEquals(
            DriveAuthResult.Failed("Google could not give access right now (code 8). Try again in a minute.", unknownBuild = false),
            auth.authorize(),
        )
    }

    @Test
    fun `an unticked Drive box is not a connection`() = runBlocking<Unit> {
        google.fromIntent = Grant.Token("ya29.x", coversDrive = false)
        val auth = auth()

        val result = auth.completeConsent(Intent())

        assertTrue(result is DriveAuthResult.Failed)
        assertNull(auth.email.value)
    }

    @Test
    fun `a finished consent always asks Drive which account was chosen`() = runBlocking<Unit> {
        memory.saved = "old@example.com"
        google.fromIntent = Grant.Token("ya29.chosen")
        whoAmIResult = { "new@example.com" }
        val auth = auth()

        assertEquals(DriveAuthResult.Authorized("new@example.com"), auth.completeConsent(Intent()))
        assertEquals("new@example.com", auth.email.value)
        assertEquals(listOf("ya29.chosen"), lookups)
    }

    @Test
    fun `a failed lookup explains itself`() = runBlocking<Unit> {
        google.grants += Grant.Token("ya29.first")
        whoAmIResult = { throw DriveException.Offline() }
        val auth = auth()

        val result = auth.authorize()

        assertTrue((result as DriveAuthResult.Failed).why.contains("No connection"))
        assertNull(auth.email.value)
    }

    @Test
    fun `another account always shows Google's account chooser`() = runBlocking<Unit> {
        memory.saved = "old@example.com"
        google.grants += Grant.Consent(pendingIntent())

        val result = auth().authorizeNewAccount()

        assertTrue(result is DriveAuthResult.NeedsConsent)
        assertEquals(listOf(null to true), google.requests)
    }

    @Test
    fun `tokens are silent, per account and briefly cached`() = runBlocking<Unit> {
        memory.saved = "owner@example.com"
        google.grants += Grant.Token("ya29.a")
        google.grants += Grant.Token("ya29.b")
        google.grants += Grant.Token("ya29.c")
        val auth = auth()

        assertEquals("ya29.a", auth.token())
        assertEquals("ya29.a", auth.token())
        assertEquals("ya29.b", auth.tokenFor("other@example.com"))
        clock.now += 11 * 60 * 1000
        assertEquals("ya29.c", auth.token())
        assertEquals(
            listOf("owner@example.com" to false, "other@example.com" to false, "owner@example.com" to false),
            google.requests,
        )
    }

    @Test
    fun `a rejected token is cleared at Google and replaced`() = runBlocking<Unit> {
        memory.saved = "owner@example.com"
        google.grants += Grant.Token("ya29.old")
        google.grants += Grant.Token("ya29.new")
        val auth = auth()

        val old = auth.token()
        auth.tokenRejected(old)

        assertEquals(listOf("ya29.old"), google.cleared)
        assertEquals("ya29.new", auth.token())
    }

    @Test
    fun `a token that needs Google's sheet means reconnect`() = runBlocking<Unit> {
        memory.saved = "owner@example.com"
        google.grants += Grant.Consent(pendingIntent())
        expectToken<DriveException.Revoked>(auth())
    }

    @Test
    fun `no account means no token`() = runBlocking<Unit> {
        expectToken<DriveException.Revoked>(auth())
        assertTrue(google.requests.isEmpty())
    }

    @Test
    fun `sign-in errors map to what sync reacts to`() = runBlocking<Unit> {
        memory.saved = "owner@example.com"
        google.grants += AuthFailure(AuthFailure.Kind.OFFLINE, "No connection")
        google.grants += AuthFailure(AuthFailure.Kind.ACCOUNT, "Sign in again")
        google.grants += AuthFailure(AuthFailure.Kind.OTHER, "Google could not give access right now (code 8). Try again in a minute.")
        val auth = auth()

        expectToken<DriveException.Offline>(auth)
        expectToken<DriveException.Revoked>(auth)
        expectToken<DriveException.Other>(auth)
    }

    @Test
    fun `health says what the lock screen needs`() = runBlocking<Unit> {
        assertEquals(LinkHealth.NOT_CONNECTED, auth().health())

        memory.saved = "owner@example.com"
        google.grants += Grant.Token("ya29.a")
        assertEquals(LinkHealth.OK, auth().health())

        google.grants += Grant.Consent(pendingIntent())
        assertEquals(LinkHealth.REVOKED, auth().health())

        google.grants += Grant.Token("ya29.b")
        whoAmIResult = { throw DriveException.Offline() }
        assertEquals(LinkHealth.OFFLINE, auth().health())

        google.grants += Grant.Token("ya29.c")
        whoAmIResult = { throw DriveException.RateLimited(1000) }
        assertEquals(LinkHealth.OK, auth().health())
    }

    @Test
    fun `disconnect revokes at Google and forgets the account`() = runBlocking<Unit> {
        memory.saved = "owner@example.com"
        val auth = auth()

        auth.disconnect()

        assertEquals(listOf("owner@example.com"), google.revoked)
        assertNull(auth.email.value)
        assertNull(memory.saved)
    }

    @Test
    fun `disconnect forgets the account even when Google cannot be reached`() = runBlocking<Unit> {
        memory.saved = "owner@example.com"
        google.revokeFails = true
        val auth = auth()

        auth.disconnect()

        assertNull(auth.email.value)
        assertNull(memory.saved)
    }

    private suspend inline fun <reified E : DriveException> expectToken(auth: GoogleDriveAuth) {
        try {
            auth.token()
            fail("expected ${E::class.simpleName}")
        } catch (e: DriveException) {
            assertTrue("got $e", e is E)
        }
    }

    private class FakeMemory : AccountMemory {
        var saved: String? = null
        override fun load(): String? = saved
        override fun save(email: String?) {
            saved = email
        }
    }

    /** Answers from a queue: a [Grant], or an [AuthFailure] to throw. */
    private class FakeGoogle : GoogleAuthorizer {
        var unavailable: String? = null
        val grants = ArrayDeque<Any>()
        var fromIntent: Grant = Grant.Token("ya29.fromIntent")
        val requests = mutableListOf<Pair<String?, Boolean>>()
        val cleared = mutableListOf<String>()
        val revoked = mutableListOf<String>()
        var revokeFails = false

        override fun unavailable(): String? = unavailable

        override suspend fun authorize(account: String?, pickAccount: Boolean): Grant {
            requests += account to pickAccount
            return when (val next = grants.removeFirst()) {
                is AuthFailure -> throw next
                else -> next as Grant
            }
        }

        override fun fromIntent(data: Intent?): Grant = fromIntent

        override suspend fun clearToken(token: String) {
            cleared += token
        }

        override suspend fun revoke(account: String) {
            if (revokeFails) throw AuthFailure(AuthFailure.Kind.OFFLINE, "No connection")
            revoked += account
        }
    }

    private companion object {
        /** PendingIntent has no public constructor, and only its identity matters here. */
        fun pendingIntent(): PendingIntent {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, PendingIntent::class.java) as PendingIntent
        }
    }
}
