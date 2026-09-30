package com.pocketide.docs

import com.pocketide.BuildConfig

/** Every outside page the docs point to, in one place so each can be checked before a release. */
internal object DocLinks {
    /** The day every fact and link below was last checked. */
    const val CHECKED_ON = "30 Sep 2026"

    /** The repository that builds and publishes PocketIDE; questions go to its issues. */
    val REPOSITORY = "https://github.com/${BuildConfig.RELEASES_REPO}"
    val ISSUES = "$REPOSITORY/issues"
    val RELEASES = "$REPOSITORY/releases"
}
