package com.pocketide.ui.shell

import com.pocketide.linux.ComputerState
import com.pocketide.vault.KeyState

/** How one checked thing stands: done, still coming, needs the owner, or only for information. */
enum class CheckStatus { DONE, WAITING, PROBLEM, INFO }

/** One row of a check list; [fix] names the action a screen offers for it, if any. */
data class CheckLine(
    val id: String,
    val title: String,
    val detail: String,
    val status: CheckStatus,
    val fix: SafetyFix? = null,
    val fixLabel: String? = null,
)

/** "You're set up when": the end of set-up says what truly works now, and what still comes. */
object SetupChecklist {
    fun lines(githubLogin: String?, driveEmail: String?, key: KeyState, computer: ComputerState, engine: String?): List<CheckLine> = listOf(
        if (githubLogin != null) {
            CheckLine("github", "GitHub", "Signed in as @$githubLogin", CheckStatus.DONE)
        } else {
            CheckLine("github", "GitHub", "Not connected. Go back to step 1.", CheckStatus.PROBLEM)
        },
        if (driveEmail != null) {
            CheckLine("drive", "Google Drive", "Connected as $driveEmail", CheckStatus.DONE)
        } else {
            CheckLine("drive", "Google Drive", "Not connected. Go back to step 2.", CheckStatus.PROBLEM)
        },
        keyLine(key),
        computerLine(computer, engine),
        CheckLine("agents", "Agents", "Each agent asks you to sign in the first time you open it.", CheckStatus.INFO),
    )

    private fun keyLine(key: KeyState): CheckLine = when (key) {
        KeyState.Ready -> CheckLine("key", "Your chats' key", "On this phone, with its two halves in Drive and GitHub", CheckStatus.DONE)
        KeyState.OnlyOnPhone -> CheckLine(
            "key", "Your chats' key",
            "Only on this phone for now. The GitHub half is saved as soon as GitHub is reachable.",
            CheckStatus.WAITING,
        )
        KeyState.NeedsPassword -> CheckLine("key", "Your chats' key", "Needs your extra password. Go back to step 2.", CheckStatus.PROBLEM)
        KeyState.None -> CheckLine("key", "Your chats' key", "Not set up. Go back to step 2.", CheckStatus.PROBLEM)
        is KeyState.Lost -> CheckLine("key", "Your chats' key", key.why, CheckStatus.PROBLEM)
    }

    private fun computerLine(computer: ComputerState, engine: String?): CheckLine = when (computer) {
        ComputerState.Ready -> CheckLine("computer", "Computer", engine?.let { "Ready · $it" } ?: "Ready", CheckStatus.DONE)
        is ComputerState.Installing -> CheckLine("computer", "Computer", "Still setting up: ${computer.step}", CheckStatus.WAITING)
        is ComputerState.Updating -> CheckLine("computer", "Computer", "Updating ${computer.what}", CheckStatus.WAITING)
        ComputerState.NotInstalled -> CheckLine("computer", "Computer", "Not set up yet. Set it up from Home when you're on Wi-Fi.", CheckStatus.WAITING)
        is ComputerState.Broken -> CheckLine("computer", "Computer", "${computer.why} ${computer.fix}", CheckStatus.PROBLEM)
    }
}

/** The fixes the safety check can offer; the Settings screen turns each into an action. */
enum class SafetyFix { SCREEN_LOCK, APP_LOCK, PRIVACY_CHECKLIST, VARIABLES, ONLY_OFFICIAL }

/** What the standing safety check reads. All of it is already on the phone: nothing is fetched. */
data class SafetyFacts(
    val screenLock: Boolean,
    val appLock: Boolean,
    val privacyChecklistDone: Boolean,
    val key: KeyState,
    /** The vault's own sentence when the key needs the owner, e.g. after a re-key. */
    val keyNotice: String?,
    val onlyOfficialAgents: Boolean,
    /** Variables (the agents can read them) as (project id or null for all projects, name). */
    val variables: List<Pair<String?, String>>,
)

/** A standing "Safety check" (screen lock, app lock, 2-step sign-in, keyring, agents, Variables). */
object SafetyCheck {
    fun lines(f: SafetyFacts): List<CheckLine> = listOf(
        if (f.screenLock) {
            CheckLine("screen-lock", "Screen lock", "On. It protects the key on this phone.", CheckStatus.DONE)
        } else {
            CheckLine(
                "screen-lock", "Screen lock", "Off. Anyone holding the phone can open it, and the key on it.",
                CheckStatus.PROBLEM, SafetyFix.SCREEN_LOCK, "Set a screen lock",
            )
        },
        if (f.appLock) {
            CheckLine("app-lock", "App lock", "On. PocketIDE asks for your fingerprint or screen lock.", CheckStatus.DONE)
        } else {
            CheckLine(
                "app-lock", "App lock", "Off. Anyone with your unlocked phone can open your chats and code.",
                CheckStatus.PROBLEM, SafetyFix.APP_LOCK, "Turn on",
            )
        },
        if (f.privacyChecklistDone) {
            CheckLine("privacy", "2-step sign-in and training switches", "You went through the privacy checklist.", CheckStatus.DONE)
        } else {
            CheckLine(
                "privacy", "2-step sign-in and training switches",
                "Not confirmed yet. 2-step sign-in on Google and GitHub is the real lock on your data.",
                CheckStatus.PROBLEM, SafetyFix.PRIVACY_CHECKLIST, "Open the checklist",
            )
        },
        keyringLine(f.key, f.keyNotice),
        if (f.onlyOfficialAgents) {
            CheckLine("agents", "Agents", "Only the official three: Claude Code, Codex and Antigravity.", CheckStatus.DONE)
        } else {
            CheckLine(
                "agents", "Agents",
                "Other verified publishers can be added, each only on your tap and clearly labelled.",
                CheckStatus.INFO, SafetyFix.ONLY_OFFICIAL, "Only official agents",
            )
        },
        variablesLine(f.variables),
    )

    private fun keyringLine(key: KeyState, notice: String?): CheckLine = when (key) {
        KeyState.Ready -> CheckLine(
            "keyring", "Keyring on GitHub",
            notice ?: "Private and yours alone. Checked on every sync; a public or shared one gets a new key.",
            if (notice == null) CheckStatus.DONE else CheckStatus.PROBLEM,
        )
        KeyState.OnlyOnPhone -> CheckLine(
            "keyring", "Keyring on GitHub",
            notice ?: "Your chats' key is only on this phone until GitHub is connected again.",
            CheckStatus.PROBLEM,
        )
        KeyState.NeedsPassword -> CheckLine("keyring", "Keyring on GitHub", "Waiting for your extra password.", CheckStatus.PROBLEM)
        KeyState.None -> CheckLine("keyring", "Keyring on GitHub", "No key yet: finish set-up first.", CheckStatus.PROBLEM)
        is KeyState.Lost -> CheckLine("keyring", "Keyring on GitHub", key.why, CheckStatus.PROBLEM)
    }

    private fun variablesLine(variables: List<Pair<String?, String>>): CheckLine {
        val risky = variables.filter { looksSecret(it.second) }
        if (risky.isEmpty()) {
            return CheckLine("variables", "Variables", "None looks like a password or key.", CheckStatus.DONE)
        }
        val names = risky.map { it.second }.distinct()
        val shown = names.take(3).joinToString(", ") + if (names.size > 3) " and ${names.size - 3} more" else ""
        return CheckLine(
            "variables", "Variables",
            if (names.size == 1) {
                "$shown looks like a secret, and agents can read Variables. Move it to Secrets."
            } else {
                "$shown look like secrets, and agents can read Variables. Move them to Secrets."
            },
            CheckStatus.PROBLEM, SafetyFix.VARIABLES, "Open Variables and Secrets",
        )
    }

    private val secretWords = setOf("TOKEN", "SECRET", "PASSWORD", "PASSWD", "PASS", "PWD", "KEY", "APIKEY", "PAT", "CREDENTIAL", "CREDENTIALS", "PRIVATE")
    private val publicWords = setOf("PUBLIC", "PUBLISHABLE")

    /**
     * A Variable name that says it holds a secret (`API_KEY`, `DB_PASSWORD`, `GITHUB_TOKEN`).
     * Keys meant to be public (`NEXT_PUBLIC_…`, Stripe's publishable key) are fine as Variables.
     */
    fun looksSecret(name: String): Boolean {
        val words = name.uppercase().split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
        if (words.any { it in publicWords }) return false
        return words.any { word -> word in secretWords || word.endsWith("APIKEY") || word.endsWith("TOKEN") || word.endsWith("SECRET") }
    }
}
