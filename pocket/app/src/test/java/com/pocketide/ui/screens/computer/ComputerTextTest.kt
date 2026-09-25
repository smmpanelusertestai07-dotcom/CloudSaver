package com.pocketide.ui.screens.computer

import com.pocketide.linux.RepairItem
import com.pocketide.linux.RepairStatus
import com.pocketide.linux.ResetPlan
import com.pocketide.ui.components.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerTextTest {
    @Test
    fun `the reset sheet lists exactly what the reset removes and keeps`() {
        val plan = ResetPlan(removes = listOf("Ubuntu", "Downloads"), keeps = listOf("Sign-ins", "Your data"))
        val sheet = ResetText.sheet(plan)
        plan.removes.forEach { assertTrue(sheet.contains("• $it")) }
        plan.keeps.forEach { assertTrue(sheet.contains("• $it")) }
        assertTrue(sheet.indexOf("Goes:") < sheet.indexOf("• Ubuntu"))
        assertTrue(sheet.indexOf("Stays:") < sheet.indexOf("• Sign-ins"))
    }

    @Test
    fun `the standard plan never claims sign-ins are lost`() {
        val sheet = ResetText.sheet(ResetPlan.STANDARD)
        assertFalse(sheet.contains("sign-ins must be done again", ignoreCase = true))
        assertTrue(sheet.contains("sign-in"))
    }

    @Test
    fun `unsaved work is named one per line`() {
        val text = ResetText.unsaved(listOf("\"Login page\": No connection.", "Your data: Drive is full."))
        assertTrue(text.contains("• \"Login page\": No connection.\n• Your data: Drive is full."))
    }

    @Test
    fun `repair shows problems first and sums up`() {
        val items = listOf(
            RepairItem("apt", RepairStatus.OK, ""),
            RepairItem("code-server", RepairStatus.NEW, "Put back."),
            RepairItem("DNS", RepairStatus.WARN, "Turn off Private DNS."),
            RepairItem("Kernel", RepairStatus.NOTE, "Old kernel."),
        )
        assertEquals(listOf("DNS", "code-server", "Kernel", "apt"), RepairText.ordered(items).map { it.what })
        assertEquals("Repair finished. 1 item could not be put right: see below.", RepairText.summary(items))
        assertEquals("Repair finished. 1 item fixed.", RepairText.summary(items.filter { it.status != RepairStatus.WARN }))
        assertEquals("Repair finished. Everything was already right.", RepairText.summary(items.take(1)))
        assertEquals(Tone.WARN, RepairText.chip(RepairStatus.WARN).tone)
    }
}
