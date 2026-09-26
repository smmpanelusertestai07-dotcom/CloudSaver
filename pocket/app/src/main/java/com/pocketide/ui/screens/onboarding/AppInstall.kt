package com.pocketide.ui.screens.onboarding

import com.pocketide.github.GitHubApi
import kotlinx.coroutines.CancellationException

/**
 * Whether PocketIDE's GitHub App is installed on the signed-in account. Signing in does not
 * install it, and without it GitHub refuses to make the keyring in the next step.
 */
internal enum class AppInstall {
    CHECKING,
    INSTALLED,
    MISSING,

    /** GitHub could not be asked (offline, or an error): set-up goes on and the next step says what is wrong. */
    UNKNOWN,
    ;

    /** Continue waits only while the App is surely missing, or the answer is still coming. */
    val canContinue: Boolean get() = this == INSTALLED || this == UNKNOWN

    /** The owner is sent to GitHub's install page, so the answer is worth asking again on return. */
    val askAgainOnReturn: Boolean get() = this != INSTALLED
}

internal suspend fun appInstall(gitHub: GitHubApi, login: String): AppInstall = try {
    if (gitHub.installedOn(login)) AppInstall.INSTALLED else AppInstall.MISSING
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    AppInstall.UNKNOWN
}
