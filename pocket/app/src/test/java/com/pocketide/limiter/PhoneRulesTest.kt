package com.pocketide.limiter

import android.app.ApplicationExitInfo
import android.provider.Settings
import com.pocketide.model.PhoneSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRulesTest {

    private val arm64 = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private fun facts(
        abis: List<String> = arm64,
        sdk: Int = 33,
        ram: Long = FOUR_GB_TOTAL,
        play: PlayServices = PlayServices.OK,
    ) = PhoneFacts(abis, sdk, ram, play)

    @Test
    fun `the owner's 4 GB Android 13 phone is supported`() {
        assertNull(Requirements.unsupportedReason(facts()))
        // Some 4 GB phones report even less than 3.6 GB to apps.
        assertNull(Requirements.unsupportedReason(facts(ram = 3_500_000_000L)))
    }

    @Test
    fun `each refusal is one plain sentence`() {
        val refused = listOf(
            facts(abis = listOf("armeabi-v7a", "armeabi")),
            facts(sdk = 28),
            facts(ram = 2_900_000_000L),
            facts(play = PlayServices.MISSING),
            facts(play = PlayServices.DISABLED),
            facts(play = PlayServices.NEEDS_UPDATE),
        )
        for (phone in refused) {
            val why = Requirements.unsupportedReason(phone)
            assertNotNull("$phone", why)
            assertTrue(why.orEmpty().endsWith("."))
        }
        assertTrue(Requirements.unsupportedReason(facts(abis = listOf("armeabi-v7a"))).orEmpty().contains("64-bit"))
        assertTrue(Requirements.unsupportedReason(facts(ram = 2_900_000_000L)).orEmpty().contains("4 GB"))
    }

    @Test
    fun `vendors are told apart by maker or brand`() {
        assertEquals(Vendor.COLOR_OS, ConditionRules.vendor("realme", "realme"))
        assertEquals(Vendor.COLOR_OS, ConditionRules.vendor("OPPO", "OPPO"))
        assertEquals(Vendor.COLOR_OS, ConditionRules.vendor("OnePlus", "OnePlus"))
        assertEquals(Vendor.MIUI, ConditionRules.vendor("Xiaomi", "Redmi"))
        assertEquals(Vendor.ONE_UI, ConditionRules.vendor("samsung", "samsung"))
        assertEquals(Vendor.VIVO, ConditionRules.vendor("vivo", "iQOO"))
        assertEquals(Vendor.HUAWEI, ConditionRules.vendor("HUAWEI", "HONOR"))
        assertEquals(Vendor.OTHER, ConditionRules.vendor("Google", "google"))
    }

    private fun ids(snapshot: PhoneSnapshot, vendor: Vendor = Vendor.OTHER, oemDone: Boolean = false) =
        ConditionRules.conditions(snapshot, vendor, oemDone).map { it.id }

    @Test
    fun `a calm phone has no conditions, and an unread one shows none`() {
        assertEquals(emptyList<String>(), ids(phone()))
        assertEquals(emptyList<String>(), ids(PhoneSnapshot.UNKNOWN.copy(backgroundRestricted = true), Vendor.COLOR_OS))
    }

    @Test
    fun `each condition has a banner with a fix`() {
        val all = phone().copy(backgroundRestricted = true, dataSaver = true, powerSave = true)
        val conditions = ConditionRules.conditions(all, Vendor.COLOR_OS, oemStepDone = false)
        assertEquals(
            listOf(ConditionRules.BATTERY_RESTRICTED, ConditionRules.OEM_BATTERY, ConditionRules.DATA_SAVER, ConditionRules.BATTERY_SAVER),
            conditions.map { it.id },
        )
        for (condition in conditions) {
            assertNotNull(condition.fixIntentAction)
            assertNotNull(condition.fixLabel)
            assertTrue(condition.explanation.endsWith("."))
        }
        assertEquals(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, conditions.first { it.id == ConditionRules.DATA_SAVER }.fixIntentAction)
    }

    @Test
    fun `the restricted bucket shows unless the battery ban already explains it`() {
        assertEquals(listOf(ConditionRules.STANDBY_RESTRICTED), ids(phone().copy(standbyBucket = 45)))
        assertEquals(emptyList<String>(), ids(phone().copy(standbyBucket = 40)))
        assertEquals(listOf(ConditionRules.BATTERY_RESTRICTED), ids(phone().copy(standbyBucket = 45, backgroundRestricted = true)))
    }

    @Test
    fun `the realme step names every switch and goes once it is done`() {
        val step = ConditionRules.conditions(phone(), Vendor.COLOR_OS, oemStepDone = false).single()
        for (words in listOf("App battery management", "Allow auto-launch", "Allow background activity", "App quick freeze")) {
            assertTrue(words, step.explanation.contains(words))
        }
        assertEquals(emptyList<String>(), ids(phone(), Vendor.COLOR_OS, oemDone = true))
        for (vendor in listOf(Vendor.MIUI, Vendor.ONE_UI, Vendor.VIVO, Vendor.HUAWEI)) {
            assertEquals(listOf(ConditionRules.OEM_BATTERY), ids(phone(), vendor))
        }
    }

    @Test
    fun `exits are explained only when rooms were running`() {
        assertNull(ExitReasons.explain(null, 5, ApplicationExitInfo.REASON_LOW_MEMORY, null, 0))
        assertNull(ExitReasons.explain(EngineTrace(emptyList(), 5), 5, ApplicationExitInfo.REASON_LOW_MEMORY, null, 0))
    }

    @Test
    fun `each exit gets its own cause`() {
        val trace = EngineTrace(listOf("claude"), bootCount = 7)
        fun cause(reason: Int?, description: String? = null, boot: Int? = 7) = ExitReasons.explain(trace, boot, reason, description, 0)?.cause

        assertEquals(StopCause.REBOOT, cause(ApplicationExitInfo.REASON_UNKNOWN, boot = 8))
        assertEquals(StopCause.MEMORY, cause(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals(StopCause.MEMORY, cause(ApplicationExitInfo.REASON_OTHER, "MemoryLimiter:AnonSwap"))
        assertEquals(StopCause.UPDATE, cause(16))
        assertEquals(StopCause.ANDROID, cause(ApplicationExitInfo.REASON_SIGNALED))
        assertEquals(StopCause.ANDROID, cause(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals(StopCause.ANDROID, cause(null))
        val stop = ExitReasons.explain(trace, 7, ApplicationExitInfo.REASON_LOW_MEMORY, null, 42)
        assertEquals(listOf("claude"), stop?.agentIds)
        assertTrue(stop?.message.orEmpty().endsWith("Nothing was lost."))
    }
}
