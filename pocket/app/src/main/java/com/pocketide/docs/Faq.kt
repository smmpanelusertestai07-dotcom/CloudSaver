package com.pocketide.docs

/** The questions owners ask, each answered in a few sentences and tied to its guide page. */
internal object Faq {
    val all: List<FaqEntry> = listOf(
        faq(
            "sign-in-twice",
            "computer",
            "Why does the computer screen ask me to sign in to GitHub again?",
            "The computer's page is GitHub's own website, and it keeps its own sign-in, apart from the app's. Sign in once with " +
                "your password and two-factor code; it stays signed in on this phone.",
            "Passkeys and \"Continue with Google\" work only in a real browser. If you use them, add a password on GitHub, or " +
                "use ⋯ > Open in Chrome.",
        ),
        faq(
            "agent-missing",
            "agents",
            "An agent is not on the screen.",
            "On a new computer, VS Code installs the agents in the first minute. Then tap the agent's icon at the top of the " +
                "page, or choose it under ⋯.",
            "If it is still missing, open ⋯ > Command palette, type \"Extensions: Show Installed\", and check it is there.",
        ),
        faq(
            "stopped-mid-task",
            "computer",
            "The computer stopped while an agent was working.",
            "GitHub stops a computer after its idle time. Typing, taps and terminal output count as activity; an agent " +
                "working alone in its panel may not, and one waiting for your answer does not. For long tasks, choose a longer " +
                "idle time in Settings (up to 4 hours); it applies to computers made after the change.",
        ),
        faq(
            "hours-used-up",
            "usage",
            "GitHub says my hours are used up.",
            "The free allowance starts again on the 1st of each month. Until then, GitHub will not start computers unless you " +
                "set a spending limit on GitHub. Stop computers when you are done to make the hours last.",
        ),
        faq(
            "code-safe",
            "your-data",
            "Is my code safe if a computer is deleted?",
            "What was pushed to GitHub is safe. Files only in the computer go with it; the card on Home warns when a " +
                "computer has code that is not on GitHub yet.",
        ),
        faq(
            "who-sees-chats",
            "safety",
            "Can anyone else see my chats?",
            "They are inside your own cloud computer, which only you can open. What you ask an agent also goes to its " +
                "company, under your account and their policy.",
        ),
        faq(
            "slow-typing",
            "computer",
            "Typing in the editor feels slow.",
            "The computer is in a GitHub data centre, so every key travels there and back. Chatting with an agent is not " +
                "affected much; a steady connection, or Wi-Fi, helps the editor.",
        ),
        faq(
            "updates",
            "agents",
            "How do the agents get updated?",
            "VS Code updates them by itself, from each publisher's own listing. PocketIDE does not pin versions.",
        ),
        faq(
            "which-secrets",
            "safety",
            "Which GitHub secrets does what? Do I need any?",
            "Your projects need none: each agent signs in with its own account. If a tool wants an API key, add it as a " +
                "Codespaces secret (Settings > Codespaces secrets): it reaches your computers as an environment variable, " +
                "for the repositories you pick.",
            "Actions secrets and variables are for builds on GitHub Actions; PocketIDE's own build uses four secrets and " +
                "two variables. Dependabot secrets are only for private package registries, and Agents secrets only for " +
                "GitHub's Copilot agent.",
        ),
        faq(
            "email-private",
            "your-data",
            "Can people see my email address?",
            "Commits pushed to a public repository show the author's email, unless GitHub hides it. Turn on \"Keep my email " +
                "addresses private\" (Settings > Keep your email private); new commits then use a no-reply address.",
        ),
        faq(
            "voice",
            "agents",
            "Can I speak instead of typing?",
            "Yes: use your keyboard's microphone key (Gboard and most phone keyboards have one). It types into the agent's " +
                "box like any text.",
        ),
        faq(
            "never-charged",
            "usage",
            "How do I make sure GitHub never charges me?",
            "Without a payment method on GitHub, use simply stops at the free allowance. With one, keep the budget at \$0 " +
                "with \"Stop usage when budget limit is reached\" (Settings > Spending limit).",
        ),
        faq(
            "agents-internet",
            "agents",
            "Can the agents use the internet? Do I need to set anything up?",
            "Yes: the computer is online, and each agent asks before it runs commands or opens sites, by its own rules. " +
                "Nothing to build: each agent brings its own tools, and PocketIDE adds two browser tools for all three. " +
                "Keys go in Codespaces secrets only if you use API keys instead of signing in.",
        ),
        faq(
            "desktop-missing",
            "computer",
            "⋯ > Desktop shows an error.",
            "Computers made before PocketIDE 4.1 have no desktop. Open the project from Home and accept the set-up update, " +
                "then rebuild the computer: ⋯ > Command palette > \"Codespaces: Rebuild Container\". Your project's files " +
                "stay; the agents' chats go with the old container.",
        ),
        faq(
            "mobile-tools",
            "builds",
            "What about Maestro, mobile-mcp and other phone-testing tools?",
            "They need an Android phone or emulator they can reach, and a cloud computer has neither. Agents can use them " +
                "on GitHub Actions, where an emulator runs, and record videos you open on GitHub.",
            "A2UI, from Google, is not a testing tool: it lets agents describe screens as data.",
        ),
        faq(
            "why-not-phone",
            "start",
            "Why not run the computer on the phone?",
            "PocketIDE 3 did, and it was heavy: gigabytes of downloads, and too slow on many phones. A cloud computer " +
                "runs the same for everyone, and the phone stays light.",
        ),
    )
}
