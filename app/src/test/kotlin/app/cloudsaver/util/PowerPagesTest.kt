package app.cloudsaver.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerPagesTest {

    @Test
    fun `realme and oppo are the same skin`() {
        assertEquals(PowerPages.Vendor.COLOR_OS, PowerPages.vendor("realme", "realme"))
        assertEquals(PowerPages.Vendor.COLOR_OS, PowerPages.vendor("OPPO", "oppo"))
        assertEquals(PowerPages.Vendor.MIUI, PowerPages.vendor("Xiaomi", "Redmi"))
        assertEquals(PowerPages.Vendor.ONE_UI, PowerPages.vendor("samsung", "samsung"))
        assertEquals(PowerPages.Vendor.PIXEL, PowerPages.vendor("Google", "google"))
        assertEquals(PowerPages.Vendor.OTHER, PowerPages.vendor("Fairphone", "FP"))
    }

    @Test
    fun `ColorOS needs background activity and auto-launch as well as battery`() {
        val ids = PowerPages.requirementsFor(PowerPages.Vendor.COLOR_OS, false).map { it.id }
        assertTrue(PowerPages.ID_BATTERY_UNRESTRICTED in ids)
        assertTrue(PowerPages.ID_BACKGROUND_ACTIVITY in ids)
        assertTrue(PowerPages.ID_AUTO_LAUNCH in ids)
    }

    @Test
    fun `a stock phone is asked only about what Android itself reports`() {
        // Battery optimisation and the per-app Restricted ban on every
        // Android; the unused-app permission reset only where it exists
        // (Android 11 and later), never as an "Off" row on a phone without it.
        val ids = PowerPages.requirementsFor(PowerPages.Vendor.PIXEL, false).map { it.id }
        assertEquals(
            listOf(PowerPages.ID_BATTERY_UNRESTRICTED, PowerPages.ID_BACKGROUND_RESTRICTION),
            ids
        )
        val with = PowerPages.requirementsFor(
            PowerPages.Vendor.PIXEL, false, backgroundRestricted = false, permissionsAutoReset = true
        )
        assertEquals(
            listOf(
                PowerPages.ID_BATTERY_UNRESTRICTED, PowerPages.ID_BACKGROUND_RESTRICTION,
                PowerPages.ID_KEEP_PERMISSIONS
            ),
            with.map { it.id }
        )
        // Armed reset and an active restriction are both "not satisfied".
        assertFalse(with.first { it.id == PowerPages.ID_KEEP_PERMISSIONS }.satisfied)
        val restricted = PowerPages.requirementsFor(PowerPages.Vendor.PIXEL, true, backgroundRestricted = true)
        assertFalse(restricted.first { it.id == PowerPages.ID_BACKGROUND_RESTRICTION }.satisfied)
        assertTrue(
            PowerPages.requirementsFor(PowerPages.Vendor.PIXEL, true, backgroundRestricted = false)
                .first { it.id == PowerPages.ID_BACKGROUND_RESTRICTION }.satisfied
        )
    }

    @Test
    fun `the Android switches are read, the OEM switches are not`() {
        // Claiming a setting is off when no API can read it teaches people to
        // ignore the app, so the maker's rows must never say "blocked" -
        // and the three Android reports must never hide behind "check this".
        val granted = PowerPages.requirementsFor(
            PowerPages.Vendor.COLOR_OS, true, backgroundRestricted = false, permissionsAutoReset = false
        )
        val readable = setOf(
            PowerPages.ID_BATTERY_UNRESTRICTED, PowerPages.ID_BACKGROUND_RESTRICTION,
            PowerPages.ID_KEEP_PERMISSIONS
        )
        for (requirement in granted) {
            assertEquals(
                "${requirement.id} readable?", requirement.id in readable, requirement.readable
            )
            if (requirement.readable) assertTrue("${requirement.id} satisfied", requirement.satisfied)
        }
        // The readable rows still carry the path, for the person who wants
        // to see the switch with their own eyes.
        for (id in readable) {
            val hint = PowerPages.pathHint(PowerPages.Vendor.COLOR_OS, id)
            assertTrue("$id has no path", !hint.isNullOrBlank())
            assertTrue("$id must name the app", hint!!.contains("CloudSaver"))
            assertTrue("$id must be a path", hint.contains("›"))
        }
    }

    @Test
    fun `a blocked battery is reported as blocked`() {
        val battery = PowerPages.requirementsFor(PowerPages.Vendor.MIUI, false)
            .first { it.id == PowerPages.ID_BATTERY_UNRESTRICTED }
        assertFalse(battery.satisfied)
    }

    @Test
    fun `huawei and honor share a skin, and it hides app launch`() {
        assertEquals(PowerPages.Vendor.HUAWEI, PowerPages.vendor("HUAWEI", "huawei"))
        assertEquals(PowerPages.Vendor.HUAWEI, PowerPages.vendor("HONOR", "honor"))
        val ids = PowerPages.requirementsFor(PowerPages.Vendor.HUAWEI, true).map { it.id }
        assertTrue(PowerPages.ID_AUTO_LAUNCH in ids)
    }

    @Test
    fun `every switch Android cannot read comes with the path to it`() {
        // "Please check it yourself" sent people to a settings app with
        // hundreds of pages. A row the app cannot read must at least say,
        // in the phone's own menu names, where the switch lives, and which
        // app to find there.
        for (vendor in PowerPages.Vendor.values()) {
            for (requirement in PowerPages.requirementsFor(vendor, false)) {
                if (requirement.readable) continue
                val hint = PowerPages.pathHint(vendor, requirement.id)
                assertTrue("$vendor ${requirement.id} has no path", !hint.isNullOrBlank())
                assertTrue("$vendor ${requirement.id} must name the app", hint!!.contains("CloudSaver"))
                assertTrue("$vendor ${requirement.id} must be a path", hint.contains("›"))
            }
        }
    }

    @Test
    fun `the battery switch has a path on every phone`() {
        // Battery optimisation is the one switch Android itself owns, so
        // even a maker this table has never heard of gets a real path.
        for (vendor in PowerPages.Vendor.values()) {
            val hint = PowerPages.pathHint(vendor, PowerPages.ID_BATTERY_UNRESTRICTED)
            assertTrue("$vendor battery path", !hint.isNullOrBlank() && hint.contains("Battery"))
        }
    }
}
