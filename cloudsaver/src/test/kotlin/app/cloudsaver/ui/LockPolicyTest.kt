package app.cloudsaver.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `the lock hides the app from recents, and takes nothing else away`() {
        // T3. The switcher is the hole a lock leaves open: Android
        // photographs the last frame on the way out, and that picture is
        // readable while the app behind it is locked.
        //
        // What is NOT here matters as much. No screen sets the flag on its
        // own any more. The free-up list used to, with the lock off, which
        // bought nothing - the phone is unlocked and the photographs are the
        // person's own - and cost them a screenshot of what they were about
        // to delete. And screenshots stay working on the Androids that can
        // separate the two, which is every one from 13 up.
        val recents = File("src/main/kotlin/app/cloudsaver/ui/components/Recents.kt").readText()
        assertTrue(
            "Android 13 and later hide the thumbnail without touching screenshots",
            recents.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU") &&
                recents.contains("activity.setRecentsScreenshotEnabled(false)")
        )
        assertTrue(
            "below 13 the window flag is the only switch there is",
            recents.substringAfter("} else {").contains("FLAG_SECURE")
        )
        assertTrue(
            "it must key on the setting, or the setting cannot be turned off again",
            recents.contains("DisposableEffect(enabled)")
        )
        val app = File("src/main/kotlin/app/cloudsaver/ui/App.kt").readText()
        assertTrue("one owner of the window", app.contains("HideWhileLocked(options.appLock)"))
        for (screen in File("src/main/kotlin/app/cloudsaver/ui/screens").listFiles().orEmpty()) {
            assertFalse(
                "${screen.name} must not hold the window flag itself",
                screen.readText().contains("FLAG_SECURE")
            )
        }
    }

    @Test
    fun `an unlock survives turning the phone, and never survives the process`() {
        // A lock that asks again on every rotation is a lock people switch
        // off. A lock that an app restores from saved state is not a lock.
        // A view model is exactly the lifetime in between.
        val vm = File("src/main/kotlin/app/cloudsaver/ui/AppViewModel.kt").readText()
        assertTrue("held by the view model", vm.contains("val unlocked = MutableStateFlow(false)"))
        val app = File("src/main/kotlin/app/cloudsaver/ui/App.kt").readText()
        assertTrue(app.contains("val unlocked by vm.unlocked.collectAsStateWithLifecycle()"))
        assertFalse(
            "never from saved instance state: that would come back unlocked",
            app.contains("rememberSaveable") && app.contains("unlocked")
        )
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
