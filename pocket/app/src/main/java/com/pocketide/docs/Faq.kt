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
            "GitHub stops a computer after its idle time. File changes and terminal output count as activity, but an agent " +
                "waiting for your answer does not. For long tasks, choose a longer idle time in Settings; it applies to computers " +
                "made after the change.",
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
            "why-not-phone",
            "start",
            "Why not run the computer on the phone?",
            "PocketIDE 3 did, and it was heavy: gigabytes of downloads, and too slow on many phones. A cloud computer " +
                "runs the same for everyone, and the phone stays light.",
        ),
    )
}
