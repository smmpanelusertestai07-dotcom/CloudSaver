package com.pocketide.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GcloudSaysTest {
    private fun problem(vararg lines: String) = GcloudSays.failure(lines.toList()).problem

    @Test
    fun `what gcloud says becomes what the owner can do`() {
        // Word for word what gcloud 587 prints without a sign-in (run on Ubuntu 26.04).
        assertEquals(
            Problem.SIGN_IN,
            problem(
                "ERROR: (gcloud.cloud-shell.ssh) You do not currently have an active account selected.",
                "Please run:", "", "  \$ gcloud auth login", "",
                "to obtain new credentials.",
            ),
        )
        assertEquals(Problem.SIGN_IN, problem("ERROR: (gcloud.cloud-shell.ssh) There was a problem refreshing your current auth tokens: invalid_grant"))
        assertEquals(
            Problem.CLOUD_SHELL,
            problem(
                "ERROR: (gcloud.cloud-shell.ssh) Your account is unverified. " +
                    "Please verify your account at https://x or at https://shell.cloud.google.com.",
            ),
        )
        assertEquals(Problem.CLOUD_SHELL, problem("ERROR: (gcloud.cloud-shell.ssh) FAILED_PRECONDITION: Accept the Terms of Service first."))
        assertEquals(Problem.CLOUD_SHELL, problem("ERROR: (gcloud.cloud-shell.ssh) RESOURCE_EXHAUSTED: weekly quota exceeded"))
        assertEquals(Problem.CLOUD_SHELL, problem("ERROR: (gcloud.cloud-shell.ssh) The Cloud Shell machine did not start."))
        assertEquals(Problem.NETWORK, problem("ERROR: gcloud crashed (ConnectionError): HTTPSConnectionPool: Max retries exceeded"))
        assertEquals(Problem.APP_UPDATE, problem("ImportError: PocketIDE: this gcloud ${GcloudSays.TUNNEL_CHANGED}; update PocketIDE."))
        assertEquals(Problem.OTHER, problem("ERROR: (gcloud.cloud-shell.ssh) Something new."))
    }

    @Test
    fun `the last words drop gcloud's prefix and terminal colours`() {
        assertEquals("Something new.", GcloudSays.lastWords(listOf("x", "ERROR: (gcloud.cloud-shell.ssh) Something new.", "")))
        assertEquals("Done.", GcloudSays.lastWords(listOf("\u001B[1mDone.\u001B[0m")))
        assertNull(GcloudSays.lastWords(listOf("", " ")))
    }

    @Test
    fun `the signed-in account and the progress come from gcloud's own lines`() {
        assertEquals("dev@example.com", GcloudSays.signedInAs(listOf("", "You are now logged in as [dev@example.com].", "Your current project is [None].")))
        assertNull(GcloudSays.signedInAs(listOf("Go to the following link in your browser")))
        assertEquals("Starting Cloud Shell (up to a minute after a break)…", GcloudSays.progress("Starting your Cloud Shell machine..."))
        assertEquals("Connecting to Cloud Shell…", GcloudSays.progress("Listening on local port [22]."))
        assertNull(GcloudSays.progress("Automatic authentication with GCP CLI tools in Cloud Shell is disabled."))
    }
}
