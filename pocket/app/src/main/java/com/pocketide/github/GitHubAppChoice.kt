package com.pocketide.github

import com.pocketide.core.Settings
import com.pocketide.core.SettingsStore

/** The GitHub App PocketIDE signs in through. Both values are public: the device flow uses no secret. */
data class GitHubApp(val clientId: String, val slug: String) {
    val configured: Boolean get() = clientId.isNotBlank()
}

/** What saving an App the owner typed did; an [Invalid] carries one sentence per wrong field. */
sealed interface GitHubAppSave {
    /** [appChanged] is true when sign-in now goes through a different App than before. */
    data class Saved(val appChanged: Boolean) : GitHubAppSave
    data class Invalid(val clientId: String?, val slug: String?) : GitHubAppSave
}

/**
 * Which GitHub App this phone uses: the one the owner entered in the app, or else the one the build
 * carries. The owner makes the App after the first APK is built, so that APK has none, and the owner
 * enters it here instead of rebuilding. Kept in this phone's private settings.
 */
class GitHubAppChoice(private val settings: SettingsStore, built: GitHubApp) {
    private val built = GitHubApp(built.clientId.trim(), built.slug.trim())

    /** Read at every use, so an App the owner enters applies at once. */
    fun current(): GitHubApp = entered(settings.settings.value) ?: built

    /** The App the owner entered, if any. */
    fun entered(): GitHubApp? = entered(settings.settings.value)

    /** True when the build carries an App to fall back on. */
    val hasBuiltIn: Boolean get() = built.configured

    fun save(clientIdInput: String, slugInput: String): GitHubAppSave {
        val clientId = clientId(clientIdInput)
        val slug = slug(slugInput)
        if (clientId == null || slug == null) {
            return GitHubAppSave.Invalid(
                clientId = if (clientId == null) BAD_CLIENT_ID else null,
                slug = if (slug == null) BAD_SLUG else null,
            )
        }
        return change { it.copy(gitHubAppClientId = clientId, gitHubAppSlug = slug) }
    }

    /** Back to the build's App (or to none). */
    fun clear(): GitHubAppSave.Saved = change { it.copy(gitHubAppClientId = "", gitHubAppSlug = "") }

    private fun change(edit: (Settings) -> Settings): GitHubAppSave.Saved {
        val before = current().clientId
        settings.update(edit)
        return GitHubAppSave.Saved(appChanged = current().clientId != before)
    }

    private fun entered(s: Settings): GitHubApp? {
        val clientId = clientId(s.gitHubAppClientId) ?: return null
        return GitHubApp(clientId, slug(s.gitHubAppSlug).orEmpty())
    }

    companion object {
        const val NEW_APP_PAGE = "https://github.com/settings/apps/new"
        const val BAD_CLIENT_ID = "Copy the Client ID from your App's page on GitHub. It starts with Iv and has 20 characters."
        const val BAD_SLUG = "Use the name from your App's address, github.com/apps/name: small letters, numbers and hyphens."

        // "Iv1." and 16 hex digits (older Apps), or "Iv23" and 16 letters or digits: 20 characters either way.
        private val CLIENT_ID = Regex("Iv1\\.[0-9a-fA-F]{16}|Iv23[A-Za-z0-9]{16}")
        private val SLUG = Regex("[a-z0-9-]{1,34}")
        private const val APPS_PATH = "github.com/apps/"

        /** The client ID, trimmed, or null when it is not one. */
        fun clientId(input: String): String? = input.trim().takeIf { CLIENT_ID.matches(it) }

        /** The App's slug, from the bare name or its pasted github.com/apps/ address; null when it is not one. */
        fun slug(input: String): String? {
            val trimmed = input.trim()
            val name = if (APPS_PATH in trimmed) trimmed.substringAfter(APPS_PATH).substringBefore('/') else trimmed
            return name.lowercase().takeIf { SLUG.matches(it) }
        }
    }
}
