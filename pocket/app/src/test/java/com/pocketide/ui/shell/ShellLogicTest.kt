package com.pocketide.ui.shell

import com.pocketide.core.Settings
import com.pocketide.model.LockReason
import com.pocketide.ui.components.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellLogicTest {
    @Test
    fun appLockComesBeforeEverything() {
        val gate = RootGate.of(appLockOn = true, unlocked = false, unsupportedReason = "32-bit", lock = LockReason.DriveDisconnected, onboardingDone = false)
        assertEquals(RootGate.AppLocked, gate)
    }

    @Test
    fun gatesFollowThePlannedOrder() {
        assertEquals(RootGate.Refused("old"), RootGate.of(true, true, "old", LockReason.DriveDisconnected, true))
        assertEquals(RootGate.Locked(LockReason.GitHubDisconnected), RootGate.of(true, true, null, LockReason.GitHubDisconnected, true))
        assertEquals(RootGate.Onboarding, RootGate.of(false, false, null, null, false))
        assertEquals(RootGate.Main, RootGate.of(false, false, null, null, true))
        assertEquals(RootGate.Main, RootGate.of(true, true, null, null, true))
    }

    @Test
    fun setUpIsNotLockedForWhatSetUpItselfConnects() {
        assertEquals(RootGate.Onboarding, RootGate.of(false, false, null, LockReason.GitHubDisconnected, false))
        assertEquals(RootGate.Onboarding, RootGate.of(false, false, null, LockReason.DriveDisconnected, false))
        // A returning owner's old phone still holds the vault: "Use here?" comes during set-up too.
        assertEquals(RootGate.Locked(LockReason.OtherPhone("Pixel 7")), RootGate.of(false, false, null, LockReason.OtherPhone("Pixel 7"), false))
        assertEquals(RootGate.Locked(LockReason.StorageFull(true)), RootGate.of(false, false, null, LockReason.StorageFull(true), false))
        assertEquals(RootGate.Refused("32-bit"), RootGate.of(false, false, "32-bit", LockReason.GitHubDisconnected, false))
    }

    @Test
    fun setUpResumesAtTheFirstUnfinishedStep() {
        assertEquals(OnboardingStep.WELCOME, OnboardingStep.resumeAt(false, false, false))
        assertEquals(OnboardingStep.DRIVE, OnboardingStep.resumeAt(true, false, false))
        assertEquals(OnboardingStep.DRIVE, OnboardingStep.resumeAt(true, true, false))
        assertEquals(OnboardingStep.COMPUTER, OnboardingStep.resumeAt(true, true, true))
    }

    @Test
    fun stepsAreNumberedOneToFour() {
        assertEquals(listOf(1, 2, 3, 4), OnboardingStep.entries.drop(1).map { it.number })
        assertEquals(OnboardingStep.NUMBERED, OnboardingStep.entries.size - 1)
        assertNull(OnboardingStep.WELCOME.previous())
        assertNull(OnboardingStep.PRIVACY.next())
        assertEquals(OnboardingStep.DRIVE, OnboardingStep.COMPUTER.previous())
    }

    @Test
    fun extraPasswordMustBeLongAndTypedTwice() {
        assertNotNull(ExtraPasswordRules.problem("", ""))
        assertNotNull(ExtraPasswordRules.problem("short", "short"))
        assertNotNull(ExtraPasswordRules.problem("          ", "          "))
        assertNotNull(ExtraPasswordRules.problem("long enough pass", "long enough pasS"))
        assertNotNull(ExtraPasswordRules.problem("long enough pass", "long enough pass "))
        assertNull(ExtraPasswordRules.problem("long enough pass", "long enough pass"))
        assertNull(ExtraPasswordRules.problem("पासवर्ड बहुत लंबा", StringBuilder("पासवर्ड बहुत लंबा")))
    }

    @Test
    fun devicePollingNeverRunsFasterThanGitHubAllows() {
        assertEquals(5000L, DeviceFlowTiming.pollDelayMs(0))
        assertEquals(5000L, DeviceFlowTiming.pollDelayMs(5))
        assertEquals(10000L, DeviceFlowTiming.pollDelayMs(10))
        assertTrue(DeviceFlowTiming.expired(1000, 1000))
        assertFalse(DeviceFlowTiming.expired(1001, 1000))
    }

    @Test
    fun diagnosticsHideSecrets() {
        val token = "ghu_" + "a".repeat(36)
        val report = Diagnostics.report(listOf("Sync" to "error $token"), listOf("Push failed with token=$token"))
        assertFalse(report.contains(token))
        assertTrue(report.contains("Sync: error [hidden]"))
        assertTrue(report.contains("Recent errors"))
        assertTrue(Diagnostics.report(emptyList(), emptyList()).contains("No recent errors."))
    }

    @Test
    fun choiceDefaultsMatchTheSettingsDefaults() {
        val defaults = Settings()
        fun <T> defaultOf(list: List<Choice<T>>): T = list.single { it.isDefault }.value
        assertEquals(defaults.theme, defaultOf(SettingChoices.theme))
        assertEquals(defaults.mobileDailyLimitMb, defaultOf(SettingChoices.dailyMobileLimitMb))
        assertEquals(defaults.maxAgents, defaultOf(SettingChoices.maxAgents))
        assertEquals(defaults.driveLimitGb, defaultOf(SettingChoices.driveLimitGb))
        assertEquals(defaults.keepChatsMonths, defaultOf(SettingChoices.keepChatsMonths))
        assertEquals(defaults.phoneChatDays, defaultOf(SettingChoices.phoneChatDays))
        assertEquals(defaults.phoneMediaDays, defaultOf(SettingChoices.phoneMediaDays))
        assertEquals(defaults.cacheDays, defaultOf(SettingChoices.cacheDays))
        assertEquals(defaults.computerUnusedDays, defaultOf(SettingChoices.computerUnusedDays))
        assertEquals(defaults.phoneLimitGb, defaultOf(SettingChoices.phoneLimitGb(0, defaults.phoneLimitGb)))
    }

    @Test
    fun choicesAreExactlyThePlansOptions() {
        assertEquals(listOf(0, 200, 500, 1000, 2000, 5000), SettingChoices.dailyMobileLimitMb.map { it.value })
        assertEquals(listOf(0, 1, 2, 3), SettingChoices.maxAgents.map { it.value })
        assertEquals(listOf(0, 3, 6, 12, 18, 36), SettingChoices.keepChatsMonths.map { it.value })
        assertEquals(listOf(30, 90, -1), SettingChoices.phoneChatDays.map { it.value })
        assertEquals(listOf(30, -1), SettingChoices.phoneMediaDays.map { it.value })
        assertEquals(listOf(30, 14), SettingChoices.cacheDays.map { it.value })
        assertEquals(listOf(90, -1), SettingChoices.computerUnusedDays.map { it.value })
    }

    @Test
    fun phoneLimitLeavesTwoGigabytesFreeButKeepsTheCurrentValue() {
        val gb = 1_000_000_000L
        assertEquals(listOf(4, 8, 16), SettingChoices.phoneLimitGb(20 * gb, 8).map { it.value })
        assertEquals(listOf(4, 8, 16, 32), SettingChoices.phoneLimitGb(20 * gb, 32).map { it.value })
        assertEquals(listOf(4, 8, 16, 32, 64, 128), SettingChoices.phoneLimitGb(0, 8).map { it.value })
    }

    @Test
    fun requirementsFlagWhatIsBelowTheMinimum() {
        val gib = 1L shl 30
        val gb = 1_000_000_000L
        val realme = PhoneFacts(33, "13", arm64 = true, playServices = true, totalRamBytes = (3.7 * gib).toLong(), freeStorageBytes = 40 * gb, screenLock = true)
        val rows = Requirements.rows(realme).associateBy { it.label }
        assertEquals(Tone.WARN, rows.getValue("Memory").tone)
        assertEquals(Tone.OK, rows.getValue("Android").tone)
        assertEquals(Tone.OK, rows.getValue("Free storage").tone)
        assertTrue(Requirements.allMet(rows.values.toList()))

        val weak = realme.copy(arm64 = false, totalRamBytes = (2.8 * gib).toLong(), freeStorageBytes = 5 * gb, playServices = false, screenLock = false)
        val weakRows = Requirements.rows(weak).associateBy { it.label }
        assertEquals(Tone.ERROR, weakRows.getValue("Processor").tone)
        assertEquals(Tone.ERROR, weakRows.getValue("Memory").tone)
        assertEquals(Tone.ERROR, weakRows.getValue("Free storage").tone)
        assertEquals(Tone.ERROR, weakRows.getValue("Google Play services").tone)
        assertEquals(Tone.WARN, weakRows.getValue("Screen lock").tone)
        assertFalse(Requirements.allMet(weakRows.values.toList()))
    }

    @Test
    fun onlyHttpsPagesLeaveTheApp() {
        assertTrue(Links.isOpenable("https://github.com/login/device"))
        assertTrue(Links.isOpenable("  HTTPS://GitHub.com/login/device  "))
        assertFalse(Links.isOpenable("http://github.com"))
        assertFalse(Links.isOpenable("javascript:alert(1)"))
        assertFalse(Links.isOpenable("https://github.com\\@evil.example"))
        assertFalse(Links.isOpenable("https://github.com%40@evil.example/"))
        assertFalse(Links.isOpenable("https://github.com\n.evil.example"))
        assertFalse(Links.isOpenable("https://github.com/\u0000"))
        assertFalse(Links.isOpenable("https://a b.example"))
        assertFalse(Links.isOpenable("intent://x#Intent;end"))
        assertFalse(Links.isOpenable("file:///data/data/com.pocketide"))
        assertFalse(Links.isOpenable("https://user@evil.example"))
        assertFalse(Links.isOpenable("https://"))
        (Links.privacyChecklist + Links.manageYourData).forEach { assertTrue(it.url, Links.isOpenable(it.url)) }
    }

    @Test
    fun limitedStackRoundTripsHelpSections() {
        assertEquals("help", LimitedStack.help(null))
        assertEquals("help:terms", LimitedStack.help("terms"))
        assertEquals("terms", LimitedStack.helpSection("help:terms"))
        assertNull(LimitedStack.helpSection("help"))
        assertEquals(listOf("help", "your-data"), LimitedStack.parse("help\nyour-data\n"))
        assertEquals(emptyList<String>(), LimitedStack.parse(""))
    }
}
