package com.pocketide.ui.shell

import com.pocketide.core.Redact
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

/** First-run steps. Welcome is not numbered; the four set-up steps are. */
enum class OnboardingStep(val number: Int) {
    WELCOME(0),
    GITHUB(1),
    DRIVE(2),
    COMPUTER(3),
    PRIVACY(4),
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

    fun problem(password: CharArray, confirm: CharArray): String? = when {
        password.isEmpty() -> "Type a password."
        password.size < MIN_LENGTH -> "Use at least $MIN_LENGTH characters."
        password.all { it.isWhitespace() } -> "Use letters, numbers or symbols, not only spaces."
        !password.contentEquals(confirm) -> "The two passwords are different."
        else -> null
    }
}

/** GitHub's device flow timing (§5.3): never poll faster than GitHub asked. */
object DeviceFlowTiming {
    const val MIN_INTERVAL_SECONDS = 5

    fun pollDelayMs(intervalSeconds: Int): Long = intervalSeconds.coerceAtLeast(MIN_INTERVAL_SECONDS) * 1000L

    fun expired(expiresAtMs: Long, nowMs: Long): Boolean = nowMs >= expiresAtMs
}

/** Diagnostics text the owner can select and share: every line goes through [Redact]. */
object Diagnostics {
    fun report(facts: List<Pair<String, String>>, errors: List<String>): String = buildString {
        for ((label, value) in facts) append(label).append(": ").append(Redact.text(value)).append('\n')
        append('\n')
        if (errors.isEmpty()) {
            append("No recent errors.")
        } else {
            append("Recent errors:\n")
            errors.forEach { append("• ").append(Redact.text(it).trim()).append('\n') }
        }
    }.trimEnd()
}
