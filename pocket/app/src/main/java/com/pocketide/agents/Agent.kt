package com.pocketide.agents

/**
 * The three official agents, each the maker's own VS Code extension from its verified publisher
 * account on Open VSX. PocketIDE installs them on the phone's computer and shows their own screens;
 * it never talks to the AI companies itself.
 */
@Suppress("LongParameterList") // One field per fact about an agent; each entry names them all.
enum class Agent(
    val displayName: String,
    val maker: String,
    /** The extension's id on Open VSX (and the VS Code Marketplace). */
    val extensionId: String,
    /** How the agent's tab is labelled in VS Code. */
    val label: String,
    /** Where, inside Linux, the agent keeps its chats. */
    val chatsFolder: String,
    val signIn: String,
    /** The sign-in command PocketIDE types into a terminal; the owner only presses Enter. */
    val signInCommand: String,
    /**
     * Where, in the computer's home folder, the agent's command-line tool and its screen both keep
     * the account, so a sign-in in the terminal signs in the screen too. Null when they sign in
     * separately, as Antigravity's do (Google keeps its terminal and its screen apart).
     */
    val sharedSignInFile: String?,
    /** The agent's own command that shows its screen, if it has one. */
    val openCommand: String?,
    /** Views that live in the activity bar, which a phone hides: PocketIDE moves them beside the others. */
    val views: List<String>,
    val dataGoesTo: String,
    val docsUrl: String,
    val privacyUrl: String,
    val termsUrl: String,
) {
    CLAUDE(
        displayName = "Claude Code",
        maker = "Anthropic",
        extensionId = "anthropic.claude-code",
        label = "Claude Code",
        chatsFolder = "~/.claude/projects",
        signIn = "A Claude account on a plan that includes Claude Code, or an Anthropic Console account.",
        signInCommand = "claude auth login",
        sharedSignInFile = ".claude/.credentials.json",
        openCommand = "claude-vscode.sidebar.open",
        views = emptyList(),
        dataGoesTo = "Your prompts, and the code and files Claude Code reads, go to Anthropic.",
        docsUrl = "https://code.claude.com/docs/en/vs-code",
        privacyUrl = "https://www.anthropic.com/legal/privacy",
        termsUrl = "https://www.anthropic.com/legal/consumer-terms",
    ),
    CODEX(
        displayName = "Codex",
        maker = "OpenAI",
        extensionId = "openai.chatgpt",
        label = "Codex",
        chatsFolder = "~/.codex/sessions",
        signIn = "A ChatGPT account on a plan that includes Codex, or an OpenAI API key.",
        signInCommand = "codex login",
        sharedSignInFile = ".codex/auth.json",
        openCommand = "chatgpt.openSidebar",
        views = emptyList(),
        dataGoesTo = "Your prompts, and the code and files Codex reads, go to OpenAI.",
        docsUrl = "https://developers.openai.com/codex/ide",
        privacyUrl = "https://openai.com/policies/privacy-policy/",
        termsUrl = "https://openai.com/policies/terms-of-use/",
    ),
    ANTIGRAVITY(
        displayName = "Antigravity",
        maker = "Google",
        extensionId = "google.google-antigravity",
        label = "Antigravity",
        chatsFolder = "~/.gemini/antigravity",
        signIn = "A Google account. Google allows its sign-in only in a real browser, so it opens in Chrome.",
        signInCommand = "agy",
        sharedSignInFile = null,
        openCommand = null,
        views = listOf("antigravity.panel"),
        dataGoesTo = "Your prompts, and the code and files Antigravity reads, go to Google.",
        docsUrl = "https://antigravity.google/docs",
        privacyUrl = "https://policies.google.com/privacy",
        termsUrl = "https://policies.google.com/terms",
    ),
    ;

    /** The publisher and name parts of [extensionId]. */
    val publisher: String get() = extensionId.substringBefore('.')
    val extensionName: String get() = extensionId.substringAfter('.')

    /** The extension's page on Open VSX, where its publisher's verified badge shows. */
    val openVsxUrl: String get() = "https://open-vsx.org/extension/$publisher/$extensionName"

    companion object {
        fun of(extensionId: String): Agent? = entries.firstOrNull { it.extensionId.equals(extensionId, ignoreCase = true) }
    }
}
