package com.pocketide.docs

import com.pocketide.BuildConfig

/** Every outside page the docs point to, in one place so each can be checked before a release. */
internal object DocLinks {
    /** The day every fact, price and link below was last checked. */
    const val CHECKED_ON = "24 Sep 2026"

    const val OPEN_VSX = "https://open-vsx.org"

    const val DRIVE_SETTINGS = "https://drive.google.com/drive/settings"
    const val DRIVE_DISCONNECT_HELP = "https://support.google.com/drive/answer/2500820"
    const val GOOGLE_STORAGE = "https://one.google.com/storage"
    const val GOOGLE_CONNECTIONS = "https://myaccount.google.com/connections"
    const val GOOGLE_SECURITY = "https://myaccount.google.com/security"
    const val GOOGLE_CLOUD_CLIENTS = "https://console.cloud.google.com/auth/clients"

    const val GITHUB_INSTALLATIONS = "https://github.com/settings/installations"
    const val GITHUB_AUTHORIZATIONS = "https://github.com/settings/apps/authorizations"
    const val GITHUB_SECURITY = "https://github.com/settings/security"
    const val GITHUB_PRICING = "https://github.com/pricing"
    const val ACTIONS_BILLING = "https://docs.github.com/en/billing/concepts/product-billing/github-actions"
    const val ACTIONS_RATES = "https://docs.github.com/en/billing/reference/actions-runner-pricing"

    const val CLAUDE_PRIVACY = "https://claude.ai/settings/data-privacy-controls"
    const val CLAUDE_DATA_USAGE = "https://code.claude.com/docs/en/data-usage"
    const val CHATGPT_DATA_CONTROLS = "https://chatgpt.com/#settings/DataControls"
    const val OPENAI_DATA_FAQ = "https://help.openai.com/en/articles/7730893-data-controls-faq"
    const val ANTIGRAVITY_SETTINGS = "https://antigravity.google/docs/settings"
    const val ANTIGRAVITY_TERMS = "https://antigravity.google/terms"

    /** Where each company shows the agent chats it keeps in the owner's account (checked 25 Sep 2026). */
    const val CLAUDE_CODE_WEB = "https://claude.ai/code"
    const val CLAUDE_REMOTE_CONTROL = "https://code.claude.com/docs/en/remote-control"
    const val CODEX_WEB = "https://chatgpt.com/codex"
    const val CODEX_CLOUD = "https://developers.openai.com/codex/ide/features"
    const val CODEX_LOCAL_SYNC_REQUEST = "https://github.com/openai/codex/issues/5609"
    const val JULES = "https://jules.google"

    const val REALME_STEPS = "https://dontkillmyapp.com/realme"
    const val ANDROID_POWER = "https://developer.android.com/topic/performance/power/power-details"

    /** The public repository that builds and publishes PocketIDE; questions go to its issues. */
    val REPOSITORY = "https://github.com/${BuildConfig.RELEASES_REPO}"
    val ISSUES = "$REPOSITORY/issues"
    val RELEASES = "$REPOSITORY/releases"

    /** The Open VSX page of an extension id "<namespace>.<name>". */
    fun openVsxPage(extensionId: String): String? {
        val namespace = extensionId.substringBefore('.', missingDelimiterValue = "")
        val name = extensionId.substringAfter('.', missingDelimiterValue = "")
        return if (namespace.isBlank() || name.isBlank()) null else "$OPEN_VSX/extension/$namespace/$name"
    }
}
