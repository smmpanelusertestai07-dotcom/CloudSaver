package com.pocketide.ui.shell

/** One outside page, opened in Chrome, with what to do there in plain words. */
data class OutsideLink(val id: String, val title: String, val what: String, val url: String, val optional: Boolean = false)

/**
 * The outside pages the shell links to. Each was checked against the company's own help page
 * (24 Sep 2026); labels are quoted as those pages show them.
 */
object Links {
    const val GOOGLE_STORAGE = "https://one.google.com/storage"
    const val DRIVE_MANAGE_APPS = "https://drive.google.com/drive/settings"
    const val GITHUB_INSTALLED_APPS = "https://github.com/settings/installations"
    const val GITHUB_AUTHORIZED_APPS = "https://github.com/settings/apps/authorizations"

    val privacyChecklist = listOf(
        OutsideLink(
            id = "claude",
            title = "Claude: model training",
            what = "Settings → Privacy → \"Help improve Claude\". Off keeps your chats out of training.",
            url = "https://claude.ai/settings/data-privacy-controls",
        ),
        OutsideLink(
            id = "chatgpt",
            title = "ChatGPT and Codex: model training",
            what = "Settings → Data controls → \"Improve the model for everyone\". Off keeps new chats out of training.",
            url = "https://chatgpt.com/#settings/DataControls",
        ),
        OutsideLink(
            id = "antigravity",
            title = "Antigravity: telemetry",
            what = "No web page has this switch: in Antigravity, open Settings → Account → \"Enable Telemetry\". It is on until you turn it off.",
            url = "https://antigravity.google/docs/settings/",
        ),
        OutsideLink(
            id = "google-2sv",
            title = "Google: 2-Step Verification",
            what = "Your Drive holds one half of your chats' key. A second step keeps it yours.",
            url = "https://myaccount.google.com/signinoptions/two-step-verification",
        ),
        OutsideLink(
            id = "github-2fa",
            title = "GitHub: two-factor authentication",
            what = "Your code and the other half of the key live here. Settings → Password and authentication.",
            url = "https://github.com/settings/security",
        ),
        OutsideLink(
            id = "copilot",
            title = "GitHub Copilot: training (if you use Copilot)",
            what = "\"Allow GitHub to use my data for AI model training\" → Disabled. It is on by default.",
            url = "https://github.com/settings/copilot/features",
            optional = true,
        ),
        OutsideLink(
            id = "codex-device-code",
            title = "Codex: device-code sign-in (only if asked)",
            what = "If Codex's sign-in asks for a device code, turn it on first in ChatGPT's Security settings.",
            url = "https://chatgpt.com/#settings/Security",
            optional = true,
        ),
    )

    /** "Manage your data" (§6.8): where each company keeps or deletes its own copy. */
    val manageYourData = listOf(
        OutsideLink(
            id = "drive-apps",
            title = "Drive → Manage apps",
            what = "See the size of PocketIDE's hidden data, or delete all of it. Use the website: the Drive app does not have this page.",
            url = DRIVE_MANAGE_APPS,
        ),
        OutsideLink(
            id = "google-storage",
            title = "Google storage",
            what = "What uses your Google storage (Gmail, Photos, Drive), and more space if you want it.",
            url = GOOGLE_STORAGE,
        ),
        OutsideLink(
            id = "github-installed",
            title = "GitHub → Installed GitHub Apps",
            what = "Which repositories PocketIDE may use. Uninstall here to remove it from every repository.",
            url = GITHUB_INSTALLED_APPS,
        ),
        OutsideLink(
            id = "github-authorized",
            title = "GitHub → Authorized GitHub Apps",
            what = "Revoke PocketIDE's sign-in. This is a separate page from the installation.",
            url = GITHUB_AUTHORIZED_APPS,
        ),
        OutsideLink(
            id = "claude-data",
            title = "Claude privacy settings",
            what = "What Anthropic keeps, and deleting it on their side.",
            url = "https://claude.ai/settings/data-privacy-controls",
        ),
        OutsideLink(
            id = "chatgpt-data",
            title = "ChatGPT data controls",
            what = "What OpenAI keeps, and deleting it on their side.",
            url = "https://chatgpt.com/#settings/DataControls",
        ),
        OutsideLink(
            id = "antigravity-data",
            title = "Antigravity settings",
            what = "Telemetry and account settings, inside Antigravity.",
            url = "https://antigravity.google/docs/settings/",
        ),
    )

    /**
     * Only web pages leave the app, and only over HTTPS to a plain host: no user info (the
     * `https://github.com@evil.example` trick), no backslashes or control characters.
     */
    fun isOpenable(url: String): Boolean {
        val lower = url.trim().lowercase()
        if (!lower.startsWith("https://")) return false
        if (lower.any { it <= ' ' || it == '\\' }) return false
        val host = lower.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
        return host.isNotEmpty() && '@' !in host
    }
}

/** The privacy checklist is done when every row that applies to everyone is ticked. */
object PrivacyChecklist {
    fun complete(ticked: Set<String>): Boolean = Links.privacyChecklist.filterNot { it.optional }.all { it.id in ticked }
}
