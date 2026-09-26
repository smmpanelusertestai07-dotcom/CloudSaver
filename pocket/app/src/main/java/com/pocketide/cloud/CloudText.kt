package com.pocketide.cloud

import com.pocketide.github.GitHubException
import com.pocketide.github.GitHubRateLimitException

/** Every sentence this module shows the owner, in one place. */
internal object CloudText {
    const val NOT_MADE = "GitHub did not make the computer. Try again in a minute."
    const val NOT_FOUND = "GitHub no longer has this cloud computer. Refresh the list."
    const val OUT_OF_HOURS =
        "GitHub is not running cloud computers for your account right now: this month's free hours or storage " +
            "are used up. They come back on the 1st of next month, or you can set a spending limit on GitHub."
    const val NOT_ALLOWED =
        "GitHub did not let PocketIDE do this. Check that PocketIDE's GitHub App has the Codespaces permissions " +
            "and can use this repository."
    const val BUSY = "GitHub is still changing this computer. Try again in a minute."
    const val FAILED = "GitHub could not start this computer. Delete it and make a new one; your code on GitHub stays."
    const val TOO_SLOW =
        "The computer is taking longer than usual. GitHub keeps starting it, so open it again in a minute."
    const val BAD_CHOICE = "GitHub could not make a computer with these choices. Pick the smallest machine and try again."
    const val REPO_NOT_READY = "GitHub is still setting up the new repository. Try again in a minute."
}

/** The HTTP statuses GitHub answers codespace calls with, by name. */
internal object HttpStatus {
    /** No answer from GitHub at all (an empty reply). */
    const val NONE = 0
    const val BAD_REQUEST = 400
    const val PAYMENT_REQUIRED = 402
    const val FORBIDDEN = 403
    const val NOT_FOUND = 404
    const val CONFLICT = 409
    const val UNPROCESSABLE = 422
}

/** A plain sentence for what GitHub says about codespaces, where its general one would be vague. */
internal object CloudErrors {
    fun of(e: GitHubException): GitHubException = when {
        e is GitHubRateLimitException -> e
        e.status == HttpStatus.PAYMENT_REQUIRED -> GitHubException(CloudText.OUT_OF_HOURS, e.status)
        e.status == HttpStatus.FORBIDDEN -> GitHubException(CloudText.NOT_ALLOWED, e.status)
        e.status == HttpStatus.CONFLICT -> GitHubException(CloudText.BUSY, e.status)
        e.status == HttpStatus.NOT_FOUND -> GitHubException(CloudText.NOT_FOUND, e.status)
        e.status == HttpStatus.BAD_REQUEST || e.status == HttpStatus.UNPROCESSABLE -> GitHubException(CloudText.BAD_CHOICE, e.status)
        else -> e
    }
}
