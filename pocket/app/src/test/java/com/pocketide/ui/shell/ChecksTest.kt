package com.pocketide.ui.shell

import com.pocketide.linux.ComputerState
import com.pocketide.vault.KeyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksTest {
    private fun setup(
        login: String? = "octo",
        email: String? = "owner@example.com",
        key: KeyState = KeyState.Ready,
        computer: ComputerState = ComputerState.Ready,
        engine: String? = "Ubuntu 24.04.5 · engine 4.138.0",
    ) = SetupChecklist.lines(login, email, key, computer, engine).associateBy { it.id }

    @Test
    fun aFinishedSetUpIsDoneExceptTheAgentsOwnSignIns() {
        val lines = setup()
        assertEquals(listOf("github", "drive", "key", "computer", "agents"), lines.keys.toList())
        assertEquals("Signed in as @octo", lines.getValue("github").detail)
        assertEquals("Ready · Ubuntu 24.04.5 · engine 4.138.0", lines.getValue("computer").detail)
        assertTrue(lines.values.filter { it.id != "agents" }.all { it.status == CheckStatus.DONE })
        // Nothing can tell whether an agent is signed in, so it is never claimed.
        assertEquals(CheckStatus.INFO, lines.getValue("agents").status)
    }

    @Test
    fun whatIsStillComingIsNotShownAsDone() {
        val installing = setup(computer = ComputerState.Installing("Unpacking Ubuntu…", 0.4f, 1, 2), engine = null)
        assertEquals(CheckStatus.WAITING, installing.getValue("computer").status)
        assertTrue(installing.getValue("computer").detail.contains("Unpacking Ubuntu"))
        val notSetUp = setup(computer = ComputerState.NotInstalled).getValue("computer")
        assertEquals(CheckStatus.WAITING, notSetUp.status)
        assertEquals("Not set up yet. Set it up from Home when you're on Wi-Fi.", notSetUp.detail)
        assertEquals(CheckStatus.WAITING, setup(key = KeyState.OnlyOnPhone).getValue("key").status)
    }

    @Test
    fun problemsSayWhereToGo() {
        val lines = setup(login = null, email = null, key = KeyState.None, computer = ComputerState.Broken("Waits for Wi-Fi", "Connect to Wi-Fi."))
        assertEquals(CheckStatus.PROBLEM, lines.getValue("github").status)
        assertTrue(lines.getValue("github").detail.contains("step 1"))
        assertTrue(lines.getValue("drive").detail.contains("step 2"))
        assertEquals(CheckStatus.PROBLEM, lines.getValue("key").status)
        assertEquals("Waits for Wi-Fi Connect to Wi-Fi.", lines.getValue("computer").detail)
        assertEquals("The halves do not match.", setup(key = KeyState.Lost("The halves do not match.")).getValue("key").detail)
    }

    private val safe = SafetyFacts(
        screenLock = true,
        appLock = true,
        privacyChecklistDone = true,
        key = KeyState.Ready,
        keyNotice = null,
        onlyOfficialAgents = true,
        variables = listOf(null to "NODE_ENV", "octo/app" to "NEXT_PUBLIC_API_KEY"),
    )

    @Test
    fun aSafePhoneHasNothingToFix() {
        val lines = SafetyCheck.lines(safe)
        assertEquals(listOf("screen-lock", "app-lock", "privacy", "keyring", "agents", "variables"), lines.map { it.id })
        assertTrue(lines.all { it.status == CheckStatus.DONE })
        assertTrue(lines.all { it.fix == null })
    }

    @Test
    fun eachProblemComesWithItsFix() {
        val lines = SafetyCheck.lines(
            safe.copy(
                screenLock = false,
                appLock = false,
                privacyChecklistDone = false,
                onlyOfficialAgents = false,
                variables = listOf("octo/app" to "OPENAI_API_KEY"),
            ),
        ).associateBy { it.id }
        assertEquals(SafetyFix.SCREEN_LOCK, lines.getValue("screen-lock").fix)
        assertEquals(SafetyFix.APP_LOCK, lines.getValue("app-lock").fix)
        assertEquals(SafetyFix.PRIVACY_CHECKLIST, lines.getValue("privacy").fix)
        assertEquals(SafetyFix.VARIABLES, lines.getValue("variables").fix)
        assertTrue(lines.getValue("variables").detail.startsWith("OPENAI_API_KEY looks like a secret"))
        // Allowing verified publishers is a choice, not a fault; the switch is still offered.
        assertEquals(CheckStatus.INFO, lines.getValue("agents").status)
        assertEquals(SafetyFix.ONLY_OFFICIAL, lines.getValue("agents").fix)
        lines.values.filter { it.fix != null }.forEach { assertTrue(it.id, !it.fixLabel.isNullOrBlank()) }
    }

    @Test
    fun theHeadingCountsWhatNeedsTheOwner() {
        assertEquals("Everything here is as it should be.", SafetyCheck.summary(0))
        assertEquals("1 thing needs you.", SafetyCheck.summary(1))
        assertEquals("3 things need you.", SafetyCheck.summary(3))
    }

    @Test
    fun manySecretLookingVariablesAreSummedUpNotListedEndlessly() {
        val names = listOf("A_TOKEN", "B_SECRET", "C_PASSWORD", "D_KEY", "E_PAT")
        val line = SafetyCheck.lines(safe.copy(variables = names.map { null to it })).single { it.id == "variables" }
        assertEquals("A_TOKEN, B_SECRET, C_PASSWORD and 2 more look like secrets, and agents can read Variables. Move them to Secrets.", line.detail)
    }

    @Test
    fun anExposedKeyringIsAProblemEvenWithAReadyKey() {
        val notice = "PocketIDE made a new key because pocketide-keyring became public."
        val lines = SafetyCheck.lines(safe.copy(keyNotice = notice)).associateBy { it.id }
        assertEquals(CheckStatus.PROBLEM, lines.getValue("keyring").status)
        assertEquals(notice, lines.getValue("keyring").detail)
        val pending = SafetyCheck.lines(safe.copy(key = KeyState.OnlyOnPhone)).single { it.id == "keyring" }
        assertEquals(CheckStatus.PROBLEM, pending.status)
        assertEquals(SafetyFix.SAVE_KEY_NOW, pending.fix)
        val alone = SafetyCheck.lines(safe.copy(key = KeyState.OnlyOnPhone, gitHubConnected = false)).single { it.id == "keyring" }
        assertEquals(SafetyFix.RECONNECT_GITHUB, alone.fix)
        assertEquals("Reconnect GitHub", alone.fixLabel)
    }

    @Test
    fun secretLookingNamesAreCaughtAndPublicOnesAreNot() {
        listOf(
            "API_KEY", "OPENAI_API_KEY", "GITHUB_TOKEN", "ACCESSTOKEN", "DB_PASSWORD", "DB_PASS", "SSH_PRIVATE_KEY",
            "CLIENT_SECRET", "AWS_SECRET_ACCESS_KEY", "npm_token", "Stripe-Secret", "GH_PAT", "GOOGLE_CREDENTIALS",
        ).forEach { assertTrue(it, SafetyCheck.looksSecret(it)) }
        listOf(
            "NODE_ENV", "PORT", "NEXT_PUBLIC_API_KEY", "STRIPE_PUBLISHABLE_KEY", "PUBLIC_KEY", "KEYBOARD_LAYOUT",
            "MONKEY_MODE", "TOKENIZER_MODEL", "PASSPORT_COUNTRY", "", "___",
        ).forEach { assertFalse(it, SafetyCheck.looksSecret(it)) }
    }
}
