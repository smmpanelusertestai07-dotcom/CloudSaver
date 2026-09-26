package com.pocketide.docs

import com.pocketide.BuildConfig

/** Every outside page the docs point to, in one place so each can be checked before a release. */
internal object DocLinks {
    /** The day every fact, price and link below was last checked. */
    const val CHECKED_ON = "26 Sep 2026"

    const val CODESPACES_BILLING = "https://docs.github.com/en/billing/concepts/product-billing/github-codespaces"
    const val CODESPACES_SECURITY = "https://docs.github.com/en/codespaces/reference/security-in-github-codespaces"
    const val CODESPACES_TIMEOUT =
        "https://docs.github.com/en/codespaces/setting-your-user-preferences/setting-your-timeout-period-for-github-codespaces"
    const val CODESPACES_SETTINGS = "https://github.com/settings/codespaces"
    const val CODESPACES_LIST = "https://github.com/codespaces"
    const val ACTIONS_BILLING = "https://docs.github.com/en/billing/concepts/product-billing/github-actions"
    const val BILLING_USAGE = "https://github.com/settings/billing/usage"
    const val BUDGETS = "https://github.com/settings/billing/budgets"
    const val GITHUB_EMAILS = "https://github.com/settings/emails"

    const val GITHUB_TERMS = "https://docs.github.com/en/site-policy/github-terms/github-terms-of-service"
    const val GITHUB_ADDITIONAL_TERMS =
        "https://docs.github.com/en/site-policy/github-terms/github-terms-for-additional-products-and-features"
    const val GITHUB_PRIVACY = "https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement"
    const val GITHUB_AUTHORIZATIONS = "https://github.com/settings/apps/authorizations"
    const val GITHUB_NEW_APP = "https://github.com/settings/apps/new"

    const val CLAUDE_PRIVACY = "https://claude.ai/settings/data-privacy-controls"
    const val CLAUDE_DATA_USAGE = "https://code.claude.com/docs/en/data-usage"
    const val CHATGPT_DATA_CONTROLS = "https://chatgpt.com/#settings/DataControls"
    const val OPENAI_DATA_FAQ = "https://help.openai.com/en/articles/7730893-data-controls-faq"
    const val ANTIGRAVITY_SETTINGS = "https://antigravity.google/docs/settings"
    const val ANTIGRAVITY_TERMS = "https://antigravity.google/terms"

    /** The repository that builds and publishes PocketIDE; questions go to its issues. */
    val REPOSITORY = "https://github.com/${BuildConfig.RELEASES_REPO}"
    val ISSUES = "$REPOSITORY/issues"
    val RELEASES = "$REPOSITORY/releases"
}
