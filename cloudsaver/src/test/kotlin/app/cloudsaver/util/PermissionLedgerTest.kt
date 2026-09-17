package app.cloudsaver.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The ledger names every permission the shipped app holds, and what it never
 * asks for is provably absent.
 */
class PermissionLedgerTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val ledger = File("src/main/kotlin/app/cloudsaver/util/PermissionLedger.kt").readText()
    private val strings = File("src/main/res/values/strings.xml").readText()

    /** What the manifest declares, minus what it declares only to remove. */
    private fun declared(): List<String> =
        Regex("""<uses-permission\s+android:name="([^"]+)"(?:\s+android:maxSdkVersion="\d+")?\s*/>""")
            .findAll(manifest).map { it.groupValues[1] }.toList()

    @Test
    fun `every permission in the manifest has a name and a purpose`() {
        val own = declared()
        assertTrue("the manifest lists permissions", own.size >= 10)
        for (permission in own) {
            val short = permission.substringAfterLast('.')
            assertTrue("$permission must be explained in the ledger", ledger.contains(short))
        }
    }

    @Test
    fun `the permissions libraries merge in are explained too`() {
        // These are in the built APK and not in this manifest: the scheduler,
        // the lock library and Android's own sealed-receiver permission add
        // them. A ledger built from the manifest that shipped shows them, so
        // they need words rather than an Android name.
        for (merged in listOf(
            "WAKE_LOCK", "RECEIVE_BOOT_COMPLETED", "USE_FINGERPRINT",
            "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        )) {
            assertTrue("$merged must be explained", ledger.contains(merged))
        }
    }

    @Test
    fun `the ledger is read from the phone, not written by hand`() {
        assertTrue(ledger.contains("PackageManager.GET_PERMISSIONS"))
        assertTrue(ledger.contains("info.requestedPermissions"))
        assertTrue(ledger.contains("REQUESTED_PERMISSION_GRANTED"))
        // Usage access is an app-op, not a grant: the flag would say "off" while it is on.
        assertTrue(ledger.contains("PACKAGE_USAGE_STATS -> UsageVerifier.hasUsageAccess(context)"))
    }

    @Test
    fun `internet is never asked for, and that is enforced in the manifest`() {
        // The claim on the screen is checkable: the manifest removes the
        // permission from every library that would merge it in.
        val removal = Regex("""android:name="android\.permission\.INTERNET"\s+tools:node="remove"""")
        assertTrue(removal.containsMatchIn(manifest))
        assertFalse(declared().contains("android.permission.INTERNET"))
        assertTrue(ledger.contains("never_internet"))
        assertTrue(strings.contains("<string name=\"never_internet\">Internet</string>"))
    }

    @Test
    fun `what the app never asks for is named on the screen`() {
        for (absent in listOf("never_camera", "never_location", "never_microphone", "never_contacts", "never_all_files")) {
            assertTrue("$absent must be in the ledger", ledger.contains(absent))
            assertTrue("$absent must have a string", strings.contains("name=\"$absent\""))
        }
        // And none of them is in the manifest.
        for (name in listOf("CAMERA", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "RECORD_AUDIO", "READ_CONTACTS", "MANAGE_EXTERNAL_STORAGE", "READ_SMS")) {
            assertFalse("the manifest must not ask for $name", manifest.contains("android.permission.$name"))
        }
    }
}
