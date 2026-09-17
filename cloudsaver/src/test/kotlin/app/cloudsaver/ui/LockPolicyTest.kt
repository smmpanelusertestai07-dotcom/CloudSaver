package app.cloudsaver.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app lock fails closed, and stays that way.
 *
 * The shipped bug: `canAuthenticate != SUCCESS` answered "unlocked", so on
 * phones where the combined biometric-or-credential query misreports - API 29
 * commonly - the lock silently opened exactly where it was supposed to hold.
 * These assertions read the source because the failure mode is a device
 * behaviour no JVM test can reproduce; what CAN be pinned down is that the
 * fail-open branch never comes back.
 */
class LockPolicyTest {

    private val lock = File("src/main/kotlin/app/cloudsaver/ui/Lock.kt").readText()

    @Test
    fun `nothing in the lock ever answers unlocked without authentication`() {
        assertFalse(
            "the fail-open branch (auto-unlock when the check fails) must not exist",
            lock.contains("onResult(true)")
        )
        assertTrue(
            "an unstartable prompt is a denial, never a pass",
            lock.contains("onResult(Outcome.Denied)")
        )
    }

    @Test
    fun `the keyguard is the authority, not canAuthenticate alone`() {
        assertTrue(
            "deviceSecure must gate on KeyguardManager.isDeviceSecure",
            lock.contains("isDeviceSecure")
        )
        assertTrue(
            "enabling requires a verifiable method",
            lock.contains("fun canEnable")
        )
    }

    @Test
    fun `authenticators are chosen per SDK`() {
        // STRONG|DEVICE_CREDENTIAL is rejected below API 30 by the library;
        // a single hard-coded set breaks one side or the other.
        assertTrue(lock.contains("Build.VERSION.SDK_INT >= 30"))
        assertTrue(lock.contains("BIOMETRIC_STRONG"))
        assertTrue(lock.contains("DEVICE_CREDENTIAL"))
    }

    @Test
    fun `lockout and missing-credential are distinct, visible outcomes`() {
        for (outcome in listOf("LockedOut", "NoMethod", "Denied", "Unlocked")) {
            assertTrue("Outcome.$outcome must exist", lock.contains(outcome))
        }
        assertTrue(
            "ERROR_LOCKOUT must map to the lockout outcome, not a generic denial",
            lock.contains("ERROR_LOCKOUT")
        )
    }

    @Test
    fun `enabling the lock verifies identity first`() {
        val options = File(
            "src/main/kotlin/app/cloudsaver/ui/screens/OptionsScreen.kt"
        ).readText()
        assertTrue(
            "the switch must authenticate before setAppLock(true)",
            options.contains("Lock.authenticate") &&
                options.contains("Outcome.Unlocked")
        )
        assertTrue(
            "no screen lock on the phone must refuse with the reason",
            options.contains("lock_enable_needs_credential")
        )
    }

    @Test
    fun `the two screens worth hiding are hidden from screenshots`() {
        // T3: the lock, and the one screen that lists photographs by name
        // beside a button that removes them.
        for (screen in listOf("LockedScreen.kt", "ReclaimScreen.kt")) {
            val text = File("src/main/kotlin/app/cloudsaver/ui/screens/$screen").readText()
            assertTrue("$screen must set FLAG_SECURE", text.contains("SecureScreen()"))
        }
    }

    @Test
    fun `a removed screen lock disables the app lock visibly, never silently`() {
        val app = File("src/main/kotlin/app/cloudsaver/ui/App.kt").readText()
        assertTrue(
            "the NoMethod outcome must route to the visible self-disable",
            app.contains("disableLockNoCredential")
        )
        val vm = File("src/main/kotlin/app/cloudsaver/ui/AppViewModel.kt").readText()
        assertTrue(
            "the self-disable must reach Activity as a Problem entry",
            vm.contains("lock_disabled_no_credential")
        )
    }
}
