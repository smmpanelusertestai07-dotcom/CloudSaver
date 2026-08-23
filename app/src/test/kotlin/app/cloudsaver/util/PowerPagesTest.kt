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
    fun `a stock phone is only asked about battery`() {
        val ids = PowerPages.requirementsFor(PowerPages.Vendor.PIXEL, false).map { it.id }
        assertEquals(listOf(PowerPages.ID_BATTERY_UNRESTRICTED), ids)
    }

    @Test
    fun `battery state is read, the OEM switches are not`() {
        // Claiming a setting is off when no API can read it teaches people to
        // ignore the app, so those rows must never say "blocked".
        val granted = PowerPages.requirementsFor(PowerPages.Vendor.COLOR_OS, true)
        val battery = granted.first { it.id == PowerPages.ID_BATTERY_UNRESTRICTED }
        assertTrue(battery.readable)
        assertTrue(battery.satisfied)
        for (other in granted.filter { it.id != PowerPages.ID_BATTERY_UNRESTRICTED }) {
            assertFalse("${other.id} must not claim to be readable", other.readable)
        }
    }

    @Test
    fun `a blocked battery is reported as blocked`() {
        val battery = PowerPages.requirementsFor(PowerPages.Vendor.MIUI, false)
            .first { it.id == PowerPages.ID_BATTERY_UNRESTRICTED }
        assertFalse(battery.satisfied)
    }
}
