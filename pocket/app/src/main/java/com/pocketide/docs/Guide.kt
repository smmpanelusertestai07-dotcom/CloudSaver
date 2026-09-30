package com.pocketide.docs

import com.pocketide.agents.Agent
import com.pocketide.linux.LinuxPins

/** The guide: short pages in reading order. Facts that change carry the day they were checked. */
internal object Guide {
    private val start = section(
        "start",
        "Start here",
        "What PocketIDE is, and the three steps to your first agent.",
        p(
            "PocketIDE puts a Linux computer inside the app: Ubuntu ${LinuxPins.UBUNTU_VERSION} LTS with VS Code, running on this " +
                "phone. The official agents from Anthropic, OpenAI and Google work on it, each full screen in its own screen, " +
                "and keep working while you use other apps.",
        ),
        steps(
            "On Home, tap Set up. It installs Ubuntu, VS Code and the three agents (about 1 GB to download; Wi-Fi is best).",
            "Tap Claude Code, Codex or Antigravity. Its own screen opens, full screen.",
            "Sign in to that agent once, with the button on its screen: the sign-in page opens in Chrome and comes back " +
                "by itself.",
        ),
        table(
            listOf("What", "Where"),
            row("Your projects", "~/projects on the computer, inside PocketIDE"),
            row("Chats and agent sign-ins", "On the computer, kept by each agent"),
            row("Keys (API keys, tokens)", "Sealed on this phone; the agents see them"),
            row("PocketIDE's own server", "None: there is no PocketIDE account"),
        ),
        link("Open the Computer screen", "app:computer"),
    )

    private val computer = section(
        DocsContent.COMPUTER_ID,
        "Your computer",
        "Ubuntu on the phone: what is on it, how it stays up to date, and how to put it right.",
        p(
            "The computer is Ubuntu ${LinuxPins.UBUNTU_VERSION} LTS (\"${LinuxPins.UBUNTU_CODENAME}\"), run by PRoot inside PocketIDE: no " +
                "root, no virtual machine, nothing changed on the phone itself. It lives in PocketIDE's private storage, which no " +
                "other app can read.",
        ),
        bullets(
            "Installed: git, curl, Python 3 (with venv), Node.js and npm, ripgrep, jq, SQLite, SSH, GitHub's gh, and VS Code " +
                "for the web (code-server ${LinuxPins.CODE_SERVER_VERSION}). Agents install more with apt when a project needs it.",
            "Every download is checked before it is used: Ubuntu's image and code-server against checksums pinned in the app, " +
                "Ubuntu's packages by apt against Ubuntu's signatures, agents against Open VSX's checksum and signature, npm " +
                "against the checksum its registry publishes.",
            "Updates run by themselves once a day while the computer is on: Ubuntu's updates (security fixes included), newer " +
                "agent releases, and the code-server this app version brings. On Wi-Fi only, unless you allow mobile data.",
            "Space: about 3 GB with the three agents. The Computer screen shows what it takes.",
        ),
        table(
            listOf("If something is wrong", "What it does"),
            row("Restart", "Stops every program on the computer. Files, sign-ins and chats stay."),
            row("Repair", "Checks each part and puts back what is missing."),
            row("Reset Ubuntu", "Rebuilds Ubuntu from scratch. Keeps projects, sign-ins, chats and agents."),
            row("Delete the computer", "Deletes all of it, projects included."),
        ),
        tip(
            "On an agent's screen, the key bar above the keyboard has Esc, Tab, Ctrl+C, the arrows and Enter, and in a " +
                "terminal, Paste. The menu (⋮) opens a terminal in your project's folder.",
        ),
        link("Open the Computer screen", "app:computer"),
    )

    private val agents = section(
        "agents",
        "The agents",
        "Claude Code, Codex and Antigravity: signing in, chats, and adding more.",
        table(
            listOf("Agent", "Sign in with", "Chats kept in"),
            *Agent.entries.map { row("${it.displayName} (${it.maker})", it.signIn, it.chatsFolder) }.toTypedArray(),
        ),
        p(
            "Each agent is its maker's own VS Code extension, from its verified publisher on Open VSX. PocketIDE shows each " +
                "one's own screen, full screen, with VS Code's other parts hidden. PocketIDE never talks to the AI companies " +
                "itself.",
        ),
        p(
            "Sign in with the agent's own button on its screen: Claude.ai Subscription (Claude Code), Sign in with " +
                "ChatGPT (Codex), Continue with Google (Antigravity). The page opens in Chrome, as Google allows its " +
                "sign-in only in a real browser, and returns to the agent on this phone by itself.",
        ),
        p(
            "Or sign in with the terminal: Sign in (the arrow at the top; for Antigravity's agy, Home > its menu) opens one with " +
                "the agent's command already typed.",
        ),
        table(
            listOf("Agent", "Typed for you", "Then"),
            *Agent.entries.map { row(it.displayName, it.signInCommand, signInThen(it)) }.toTypedArray(),
        ),
        info(
            "Antigravity signs in on its own screen: Continue with Google. Google keeps that screen and its terminal tool " +
                "(agy) signed in separately, so sign in agy in the terminal only to use agy there (Home > Antigravity's menu).",
        ),
        p(
            "More agents: Home > Add agents searches Open VSX. Only publishers Open VSX has verified can be installed, and " +
                "Microsoft's extensions cannot be (Microsoft allows them only in its own products).",
        ),
        link("Add agents", "app:agents"),
    )

    private val keys = section(
        DocsContent.KEYS_ID,
        "Keys",
        "API keys and tokens: where they go, and which ones you need.",
        p(
            "Settings > Keys holds environment variables for the agents, their command-line tools and the terminal. They are " +
                "sealed on this phone with a key in its secure hardware and never leave it. After adding one, restart code-server " +
                "(agent screen > ⋮ > Restart code-server).",
        ),
        table(
            listOf("Key", "When you need it"),
            row("ANTHROPIC_API_KEY", "Claude Code billed to an Anthropic Console account instead of a Claude plan"),
            row("OPENAI_API_KEY", "Codex billed to the OpenAI API: run printenv OPENAI_API_KEY | codex login --with-api-key once"),
            row("GEMINI_API_KEY", "Antigravity on a Gemini API key instead of a Google account"),
            row("GH_TOKEN", "gh and git push to GitHub without signing in there (a fine-grained token)"),
        ),
        p("With a plan sign-in (Claude, ChatGPT, Google), no key is needed."),
        link("Open Keys", "app:keys"),
    )

    private val projects = section(
        "projects",
        "Projects and GitHub",
        "Where your work is, and how to put it on GitHub.",
        bullets(
            "Each project is a folder in ~/projects. Pick one, or make a new one, with the Project chip on Home: the agents open in it.",
            "To work on a project from GitHub, make a new project, open the terminal and run git clone with its address.",
            "Nothing is saved outside PocketIDE. To keep a copy elsewhere, push it to GitHub: in the terminal, run gh auth " +
                "login once (it opens GitHub's page in Chrome), then git push.",
            "An agent can build and test web apps, backends and scripts here. Android apps need Google's build tools, which " +
                "exist only for x86-64 computers: build them on GitHub Actions (an agent can write the workflow).",
        ),
    )

    private val background = section(
        DocsContent.BACKGROUND_ID,
        "Agents in the background",
        "Keeping the agents working while you use other apps.",
        p(
            "While the computer is on, PocketIDE shows a notification with a Stop button, so Android keeps it running. Two " +
                "phone settings can still stop it:",
        ),
        steps(
            "Battery: Android settings > Apps > PocketIDE > Battery > Unrestricted. Some phones (Realme, OPPO, Xiaomi) also " +
                "need Allow background activity.",
            "Android 12 and newer stop an app's extra programs beyond 32 in all. On Android 14 and newer: Settings > System > " +
                "Developer options > Disable child process restrictions. On Android 12 and 13 it takes one adb command from " +
                "a computer: adb shell settings put global settings_enable_monitor_phantom_procs false",
        ),
        info("Developer options appear after tapping Build number seven times in Settings > About phone."),
    )

    private val trouble = section(
        DocsContent.TROUBLE_ID,
        "When something does not work",
        "The usual fixes, lightest first.",
        table(
            listOf("What you see", "Try"),
            row("code-server does not start", "Try again; then Computer > Restart; then Repair; then Reset Ubuntu."),
            row("An agent's screen stays empty", "Tap Reload (↻). A new agent needs a minute on its first start."),
            row("The sign-in page does not open", "Allow PocketIDE's notifications: when it is not on screen, the page waits there."),
            row("Set-up stopped", "Tap Set up again: it continues where it stopped."),
            row("Agents stop in the background", "See Agents in the background."),
            row("The phone is out of space", "Free space, or delete old projects in the terminal."),
        ),
        link("Agents in the background", "help:${DocsContent.BACKGROUND_ID}"),
    )

    private val yourData = section(
        DocsContent.YOUR_DATA_ID,
        "Your data",
        "What stays on the phone, what leaves it, and how to delete it.",
        bullets(
            "On the phone, inside PocketIDE: the computer, your projects, each agent's sign-in, settings and chats, your keys " +
                "(sealed) and PocketIDE's settings. Android's backup does not copy them.",
            "What leaves the phone: what you ask an agent, and the code it reads, goes to its company under your account " +
                "there. Downloads come from Ubuntu, code-server's GitHub releases, Open VSX, Google (Antigravity's agy) " +
                "and npm (from nodejs.org and npm's registry).",
            "Programs on the computer can reach the internet, like on any computer, and run with PocketIDE's access on the " +
                "phone: install only what you trust.",
            "Delete: Settings > Your data > Delete everything, or uninstall PocketIDE.",
        ),
        link("Open Your data", "app:data"),
    )

    private val cloudShell = section(
        DocsContent.CLOUD_SHELL_ID,
        "Google Cloud Shell",
        "Google's own Linux computer, free: set-up, limits, where the data is, and how to delete it.",
        p(
            "Cloud Shell is a Linux computer from Google, free with a Google account. The Google Cloud Shell screen (on " +
                "Home) gives one command that installs VS Code and the three agents there, each checked before use. It " +
                "opens in a Chrome tab, because Google allows its sign-in only in a browser, and asks which account to use.",
        ),
        table(
            listOf("Free limit", "Value"),
            row("Hours", "50 a week, at most 12 in one session"),
            row("When you leave", "It stops after about 40 minutes"),
            row("Home folder", "5 GB, the only part kept; deleted after 120 days without use"),
        ),
        table(
            listOf("Where", "What"),
            row("~/projects", "Your projects"),
            row("~/.claude, ~/.codex, ~/.gemini", "Each agent's chats and sign-in"),
            row("Its company", "What you ask an agent, and the code it reads"),
            row("Not there", "Drive, Photos, your Google Cloud projects, the chat lists on claude.ai or chatgpt.com"),
        ),
        bullets(
            "See it: Cloud Shell's editor (shell.cloud.google.com), the Google Cloud console, or the Google Cloud app (terminal only).",
            "Delete everything: in Cloud Shell run sudo rm -rf \$HOME, then More > Restart.",
            "Keep your account safe: use it yourself, while you work; no miners, scanners or keep-awake tricks; never share a Web Preview link.",
        ),
        link("Open Google Cloud Shell", "app:cloud-shell"),
        link("Google's limits", "https://docs.cloud.google.com/shell/docs/limitations"),
    )

    private fun signInThen(agent: Agent): String = if (agent.sharedSignInFile != null) {
        "Press Enter and sign in in Chrome. Come back: the screen reloads, signed in."
    } else {
        "Press Enter twice (Google OAuth) and sign in in Chrome. Copy the code the page shows, come back, tap the " +
            "terminal, then Paste above the keyboard and Enter. Antigravity then runs in the terminal."
    }

    private val permissions = section(
        "permissions",
        "Permissions",
        "What PocketIDE may do on your phone, and why.",
        table(
            listOf("Android permission", "Why"),
            row("INTERNET", "To set up and update the computer, and for the agents to reach their companies"),
            row("ACCESS_NETWORK_STATE", "To update on Wi-Fi only (unless you allow mobile data), and to give Linux the phone's DNS"),
            row("POST_NOTIFICATIONS", "The \"Computer is on\" notice with its Stop button, and sign-in pages that wait for you; you can refuse it"),
            row("FOREGROUND_SERVICE", "To keep the computer running while you use other apps"),
            row("FOREGROUND_SERVICE_SPECIAL_USE", "The kind of background work that is, as Android requires it named"),
            row("USE_BIOMETRIC", "App lock, with your phone's own screen lock"),
        ),
        p(
            "No storage, camera, microphone, location, contacts or accounts access. Files you attach for an agent are " +
                "picked with Android's own picker, one choice at a time.",
        ),
    )

    /** The guide, in reading order. */
    val all: List<DocSection> = listOf(start, computer, agents, keys, projects, background, trouble, yourData, cloudShell, permissions)
}
