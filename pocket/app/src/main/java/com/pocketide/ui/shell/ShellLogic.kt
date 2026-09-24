package com.pocketide.ui.shell

import com.pocketide.core.Redact
import com.pocketide.core.Settings
import com.pocketide.model.LockReason

/** What the root shows, in order of precedence (PocketRoot). */
sealed interface RootGate {
    data object AppLocked : RootGate
    data class Refused(val why: String) : RootGate
    data class Locked(val reason: LockReason) : RootGate
    data object Onboarding : RootGate
    data object Main : RootGate

    companion object {
        /**
         * The app lock comes first: nothing, not even why the app is locked, shows to someone
         * who has not passed it. An unsupported phone is refused before any account screen.
         */
        fun of(
            appLockOn: Boolean,
            unlocked: Boolean,
            unsupportedReason: String?,
            lock: LockReason?,
            onboardingDone: Boolean,
        ): RootGate = when {
            appLockOn && !unlocked -> AppLocked
            unsupportedReason != null -> Refused(unsupportedReason)
            lock != null -> Locked(lock)
            !onboardingDone -> Onboarding
            else -> Main
        }
    }
}

/**
 * First-run steps. Welcome is not numbered; the four set-up steps are, each with how long it
 * usually takes so the owner knows what they are starting.
 */
enum class OnboardingStep(val number: Int, val usualTime: String?) {
    WELCOME(0, null),
    GITHUB(1, "About 1 min"),
    DRIVE(2, "About 1 min"),
    COMPUTER(3, "About 10 min on Wi-Fi"),
    PRIVACY(4, "About 3 min"),
    ;

    fun previous(): OnboardingStep? = entries.getOrNull(ordinal - 1)
    fun next(): OnboardingStep? = entries.getOrNull(ordinal + 1)

    companion object {
        const val NUMBERED = 4

        /**
         * Where set-up resumes after the app was closed half way: the first step whose work is
         * not done yet. The computer and the checklist are never skipped by themselves.
         */
        fun resumeAt(gitHubConnected: Boolean, driveConnected: Boolean, keyReady: Boolean): OnboardingStep = when {
            !gitHubConnected -> WELCOME
            !driveConnected || !keyReady -> DRIVE
            else -> COMPUTER
        }
    }
}

/** The optional extra password (§5.1): forgotten means the chats are lost, so it must be deliberate. */
object ExtraPasswordRules {
    const val MIN_LENGTH = 10

    /** Reads the fields as they are, so checking a password makes no extra copies of it. */
    fun problem(password: CharSequence, confirm: CharSequence): String? = when {
        password.isEmpty() -> "Type a password."
        password.length < MIN_LENGTH -> "Use at least $MIN_LENGTH characters."
        password.all { it.isWhitespace() } -> "Use letters, numbers or symbols, not only spaces."
        !sameText(password, confirm) -> "The two passwords are different."
        else -> null
    }

    private fun sameText(a: CharSequence, b: CharSequence): Boolean =
        a.length == b.length && a.indices.all { a[it] == b[it] }
}

/** GitHub's device flow timing (§5.3): never poll faster than GitHub asked. */
object DeviceFlowTiming {
    const val MIN_INTERVAL_SECONDS = 5

    fun pollDelayMs(intervalSeconds: Int): Long = intervalSeconds.coerceAtLeast(MIN_INTERVAL_SECONDS) * 1000L

    fun expired(expiresAtMs: Long, nowMs: Long): Boolean = nowMs >= expiresAtMs
}

/**
 * Diagnostics text the owner can select and share: every line goes through [Redact], and email
 * addresses and `key=` values are hidden too. Nothing from chats is ever put in.
 */
object Diagnostics {
    /** What the share sheet says, so the owner knows before sending it. */
    const val SHARE_TITLE = "PocketIDE diagnostics (contains no tokens, keys or chat text)"

    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val keyValue = Regex("(?i)\\b([a-z_]*key|authorization)(\\s*[=:]\\s*)([^\\s&,;]+)")
    private val bearer = Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+")

    fun report(facts: List<Pair<String, String>>, errors: List<String>): String = buildString {
        for ((label, value) in facts) append(label).append(": ").append(clean(value)).append('\n')
        append('\n')
        if (errors.isEmpty()) {
            append("No recent errors.")
        } else {
            append("Recent errors:\n")
            errors.forEach { append("• ").append(clean(it).trim()).append('\n') }
        }
    }.trimEnd()

    fun clean(text: String): String {
        val redacted = Redact.text(text).replace(email, "[email]").replace(bearer, "Bearer [hidden]")
        return keyValue.replace(redacted) { m -> "${m.groupValues[1]}${m.groupValues[2]}[hidden]" }
    }
}

/**
 * "Set up on mobile data" after the owner confirmed the size: big downloads are allowed on
 * mobile data and today's limit is raised far enough for set-up, then both go back to what
 * they were. The data rules stay the only gate the computer's installer asks.
 */
object MobileSetup {
    /** Enough for the computer's own downloads plus what the day already used. */
    const val SETUP_LIMIT_MB = 2000

    fun allow(settings: Settings): Settings =
        settings.copy(wifiOnlyBigDownloads = false, mobileDailyLimitMb = maxOf(settings.mobileDailyLimitMb, SETUP_LIMIT_MB))

    /** Puts back what [allow] changed, leaving alone anything the owner changed in the meantime. */
    fun restore(current: Settings, before: Settings, allowed: Settings): Settings = current.copy(
        wifiOnlyBigDownloads = if (current.wifiOnlyBigDownloads == allowed.wifiOnlyBigDownloads) {
            before.wifiOnlyBigDownloads
        } else {
            current.wifiOnlyBigDownloads
        },
        mobileDailyLimitMb = if (current.mobileDailyLimitMb == allowed.mobileDailyLimitMb) {
            before.mobileDailyLimitMb
        } else {
            current.mobileDailyLimitMb
        },
    )
}
