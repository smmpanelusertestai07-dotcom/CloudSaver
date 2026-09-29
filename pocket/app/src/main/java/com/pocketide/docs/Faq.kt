package com.pocketide.docs

/** The questions owners ask, each answered in a few sentences and tied to its guide page. */
internal object Faq {
    val all: List<FaqEntry> = listOf(
        faq(
            "account",
            "start",
            "Do I need a PocketIDE account, or GitHub?",
            "No. PocketIDE has no account and no server. Each agent signs in to its own maker: a Claude, ChatGPT or Google " +
                "account, or an API key. GitHub is only needed if you want to keep your projects there.",
        ),
        faq(
            "set-up-size",
            DocsContent.COMPUTER_ID,
            "Why is the set-up so big, and can I stop it?",
            "It downloads Ubuntu, its tools, VS Code (code-server) and the three agents: about 1 GB, about 3 GB once " +
                "unpacked. You can leave the app or lose the connection: tap Set up again and it continues where it stopped.",
        ),
        faq(
            "sign-in-chrome",
            "agents",
            "Why does sign-in open Chrome?",
            "Google allows its sign-in only in a real browser, and Chrome keeps your saved passwords. The sign-in page returns " +
                "to the agent on this phone by itself, and after a sign-in in the terminal the agent's screen reloads signed " +
                "in. Only Antigravity in the terminal (agy) shows a code to copy into the terminal instead.",
        ),
        faq(
            "agents-internet",
            "agents",
            "Can the agents use the internet?",
            "Yes, like on any computer: to install packages, read documentation and call web services. Codex runs its " +
                "commands in its own sandbox, which blocks the network by default; you can choose another mode in Codex's own " +
                "settings, where it explains each one.",
        ),
        faq(
            "api-keys",
            DocsContent.KEYS_ID,
            "Where do I put an API key or a token?",
            "Settings > Keys. Each key is an environment variable that the agents, their command-line tools and the terminal " +
                "see. It is sealed on this phone and never leaves it. Restart code-server after adding one.",
        ),
        faq(
            "to-github",
            "projects",
            "How do I put a project on GitHub?",
            "Open the terminal in the project, run gh auth login once (GitHub's page opens in Chrome), then git push. Or ask " +
                "the agent to do it: it uses the same terminal.",
        ),
        faq(
            "android-builds",
            "projects",
            "Can I build an Android app here?",
            "Not on the phone itself: Google's Android build tools exist only for x86-64 computers, and a phone cannot run an " +
                "Android emulator inside an app. Push the project to GitHub and build it on GitHub Actions; an agent can write " +
                "the workflow.",
        ),
        faq(
            "more-agents",
            "agents",
            "Which other agents can I add?",
            "Any extension on Open VSX from a publisher Open VSX has verified, built for this phone or for every platform: " +
                "Home > Add agents. Microsoft's own extensions cannot be installed: Microsoft allows them only in its products.",
        ),
        faq(
            "stops-background",
            DocsContent.BACKGROUND_ID,
            "The agents stop when I switch apps.",
            "Set PocketIDE's battery use to Unrestricted, and on Android 12 and newer lift the limit on an app's extra " +
                "programs. Agents in the background shows both, step by step.",
        ),
        faq(
            "battery",
            DocsContent.COMPUTER_ID,
            "Does it drain the battery?",
            "Only while an agent works: then the phone does what a laptop would, and gets warm on long tasks. When no agent " +
                "is working, code-server waits quietly. Stop the computer from its notification when you are done.",
        ),
        faq(
            "other-apps",
            DocsContent.YOUR_DATA_ID,
            "Can other apps see my projects or chats?",
            "Not in storage: they are in PocketIDE's private storage, which Android gives no other app. code-server listens " +
                "only on the phone itself and asks for a password that only PocketIDE has. Like on a computer, though, a " +
                "program that listens on the phone's own network can be reached by other apps on the phone: a web app an " +
                "agent runs, or Antigravity's local server while its screen is open. Install only apps you trust.",
        ),
        faq(
            "old-version",
            DocsContent.COMPUTER_ID,
            "I used an earlier PocketIDE. What happens to it?",
            "Version 5 runs the computer on the phone. It removed what earlier versions kept on the phone, including the " +
                "GitHub sign-in of version 4. Codespaces you made with version 4 stay in your GitHub account until you delete " +
                "them there.",
        ),
    )
}
