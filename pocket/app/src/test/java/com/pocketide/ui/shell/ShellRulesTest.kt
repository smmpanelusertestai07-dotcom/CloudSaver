package com.pocketide.ui.shell

import com.pocketide.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellRulesTest {
    @Test
    fun mobileSetUpOpensTheDataRulesOnlyAsFarAsNeeded() {
        val defaults = Settings()
        val allowed = MobileSetup.allow(defaults)
        assertFalse(allowed.wifiOnlyBigDownloads)
        assertEquals(MobileSetup.SETUP_LIMIT_MB, allowed.mobileDailyLimitMb)
        // A limit already higher than set-up needs is never lowered.
        assertEquals(5000, MobileSetup.allow(defaults.copy(mobileDailyLimitMb = 5000)).mobileDailyLimitMb)
        // Nothing else changes.
        assertEquals(defaults.copy(wifiOnlyBigDownloads = false, mobileDailyLimitMb = MobileSetup.SETUP_LIMIT_MB), allowed)
    }

    @Test
    fun mobileSetUpPutsTheRulesBack() {
        val before = Settings(mobileDailyLimitMb = 0, wifiOnlyBigDownloads = true)
        val allowed = MobileSetup.allow(before)
        assertEquals(before, MobileSetup.restore(allowed, before, allowed))
    }

    @Test
    fun whatTheOwnerChangedDuringSetUpIsKept() {
        val before = Settings()
        val allowed = MobileSetup.allow(before)
        val ownerChangedLimit = allowed.copy(mobileDailyLimitMb = 500, theme = com.pocketide.core.ThemeMode.DARK)
        val restored = MobileSetup.restore(ownerChangedLimit, before, allowed)
        assertEquals(500, restored.mobileDailyLimitMb)
        assertTrue(restored.wifiOnlyBigDownloads)
        assertEquals(com.pocketide.core.ThemeMode.DARK, restored.theme)

        val ownerTurnedWifiOnlyOn = allowed.copy(wifiOnlyBigDownloads = true)
        val again = MobileSetup.restore(ownerTurnedWifiOnlyOn, before, allowed)
        assertTrue(again.wifiOnlyBigDownloads)
        assertEquals(before.mobileDailyLimitMb, again.mobileDailyLimitMb)
    }

    @Test
    fun diagnosticsHideEmailsKeysAndBearerTokens() {
        val report = Diagnostics.report(
            facts = listOf("Google Drive" to "ok as renu.k+test@gmail.com"),
            errors = listOf(
                "Upload failed: Authorization: Bearer ya29.short_but_secret",
                "config api_key=sk_live_1234567890 and OPENAI_API_KEY: abc123",
                "Key: AGE-SECRET-KEY-1" + "Q".repeat(58),
            ),
        )
        assertFalse(report, report.contains("renu.k+test@gmail.com"))
        assertTrue(report.contains("[email]"))
        assertFalse(report, report.contains("ya29.short_but_secret"))
        assertFalse(report, report.contains("sk_live_1234567890"))
        assertFalse(report, report.contains("abc123"))
        assertFalse(report, report.contains("QQQQQQQQ"))
    }

    @Test
    fun diagnosticsKeepOrdinaryWordsAboutKeys() {
        assertEquals("Wrong key for this vault", Diagnostics.clean("Wrong key for this vault"))
        assertEquals("keyring private", Diagnostics.clean("keyring private"))
    }

    @Test
    fun hostileTextFromLinuxCannotForgeReportLines() {
        // A failure message from inside Linux is one bullet, however it is shaped.
        val report = Diagnostics.report(emptyList(), listOf("  set-up stopped  \n", "exit 1\n• Key: ready\r\nNo recent errors."))
        assertEquals("Recent errors:\n• set-up stopped\n• exit 1 • Key: [hidden] No recent errors.", report)
        assertTrue(Diagnostics.SHARE_TITLE.contains("no tokens, keys or chat text"))
    }

    @Test
    fun everyNumberedStepSaysHowLongItTakes() {
        assertNull(OnboardingStep.WELCOME.usualTime)
        OnboardingStep.entries.drop(1).forEach { assertNotNull(it.name, it.usualTime) }
        assertTrue(OnboardingStep.COMPUTER.usualTime!!.contains("Wi-Fi"))
    }

    @Test
    fun privacyChecklistNeedsEveryRowButTheOptionalOnes() {
        val required = Links.privacyChecklist.filterNot { it.optional }.map { it.id }.toSet()
        assertTrue(required.containsAll(setOf("claude", "chatgpt", "antigravity", "google-2sv", "github-2fa")))
        assertTrue(PrivacyChecklist.complete(required))
        assertFalse(PrivacyChecklist.complete(required - "github-2fa"))
        assertFalse(PrivacyChecklist.complete(emptySet()))
        assertFalse(PrivacyChecklist.complete(setOf("copilot", "codex-device-code")))
        assertEquals(Links.privacyChecklist.size, Links.privacyChecklist.map { it.id }.toSet().size)
    }

    @Test
    fun privacyRowsUseTheVendorsOwnLabels() {
        val byId = Links.privacyChecklist.associateBy { it.id }
        assertTrue(byId.getValue("claude").what.contains("\"Help improve Claude\""))
        assertTrue(byId.getValue("chatgpt").what.contains("\"Improve the model for everyone\""))
        assertTrue(byId.getValue("antigravity").what.contains("\"Enable Telemetry\""))
        assertEquals("https://github.com/settings/copilot/features", byId.getValue("copilot").url)
        assertTrue(byId.getValue("copilot").optional)
    }

    @Test
    fun avatarsAreShrunkWhileDecodingAndHugeOnesRefused() {
        assertEquals(1, AvatarSize.sampleSize(160, 160, 160))
        assertEquals(2, AvatarSize.sampleSize(460, 460, 160))
        assertEquals(4, AvatarSize.sampleSize(640, 640, 160))
        assertEquals(4, AvatarSize.sampleSize(640, 8000, 160))
        assertEquals(32, AvatarSize.sampleSize(8000, 8000, 160))
        assertNull(AvatarSize.sampleSize(40_000, 40_000, 160))
        assertNull(AvatarSize.sampleSize(0, 100, 160))
        assertNull(AvatarSize.sampleSize(-1, -1, 160))
    }

    @Test
    fun countdownSurvivesAbsurdExpiryTimes() {
        assertFalse(Formats.countdown(Long.MAX_VALUE).startsWith("-"))
        assertEquals("0:00", Formats.countdown(Long.MIN_VALUE))
    }
}
