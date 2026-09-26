package com.pocketide.docs

import com.pocketide.agents.Agent
import com.pocketide.cloud.ComputerConfig
import com.pocketide.usage.Allowance

/** The guide: short pages in reading order. Facts that change carry the day they were checked. */
internal object Guide {
    private val start = section(
        "start",
        "Start here",
        "What PocketIDE is, and the four steps to your first agent.",
        p(
            "PocketIDE is agentic development on your phone. Your computer is your own GitHub Codespace: Ubuntu with the " +
                "real VS Code, on GitHub's servers. The official agents from Anthropic, OpenAI and Google work there, " +
                "full screen in this app, and keep working when you close it.",
        ),
        steps(
            "Sign in with GitHub. GitHub's page opens in Chrome.",
            "Choose which repositories PocketIDE may use.",
            "Make a new project, or open a repository. GitHub makes its cloud computer in a minute or two.",
            "Tap Claude Code, Codex or Antigravity, and sign in to that agent once, in its own screen.",
        ),
        table(
            listOf("What", "Where"),
            row("Code", "Your GitHub repositories, private by default"),
            row("Computer", "GitHub Codespaces, one per project"),
            row("Builds and tests", "GitHub Actions"),
            row("Chats", "Inside each computer, kept by each agent"),
            row("This phone", "Only your sign-in and settings"),
        ),
        p(
            "Why this way: after the AI companies' desktop apps, their IDE extensions are the most developed way to use their " +
                "agents, and on a phone they are the one official way with a full screen of their own. Codespaces gives them a " +
                "real computer. GitHub keeps code, computer and builds in one account, with no server of ours in between.",
        ),
        p(
            "What you can build: websites, web apps, backends, Android, Flutter and React Native apps. iOS and macOS apps build " +
                "on GitHub's macOS machines, Windows apps on its Windows machines. Not possible: running an iPhone or Mac " +
                "simulator live, or a 3D game engine's editor.",
        ),
    )

    private val computer = section(
        "computer",
        "Your cloud computer",
        "What a codespace is, when it runs, and when GitHub deletes it.",
        p(
            "Each project gets its own GitHub Codespace: a private virtual machine that only you can open. By default it has " +
                "2 cores, 8 GB of RAM and 32 GB of disk, in GitHub's region nearest to you.",
        ),
        bullets(
            "It runs only while it is used, and stops by itself after 30 minutes idle (choose 15 minutes to 4 hours in Settings). " +
                "An agent's work counts as use: GitHub counts file changes and terminal output as activity.",
            "Stopped, it keeps its files. Starting it again takes about a minute.",
            "GitHub deletes a computer left unused for 30 days (choose 7, 14 or 30). Your code on GitHub stays: push your " +
                "work, and nothing is lost.",
            "PocketIDE adds three files in ${ComputerConfig.FOLDER}/ to the project: GitHub's default image, the three " +
                "agents, and settings for a phone screen. Your own dev container set-up is not touched.",
            "Each time the computer starts, PocketIDE's settings are put back, so a change made in a browser, or synced from " +
                "another device, does not stick.",
        ),
        tip(
            "On the computer screen, the key bar has Esc, Tab, Ctrl, the arrows, Ctrl C and Send. ⋯ opens the menu: the agents, " +
                "the terminal, the command palette, Reload, Open in Chrome and Stop computer.",
        ),
        link("Codespaces security, by GitHub", DocLinks.CODESPACES_SECURITY),
    )

    private val agents = section(
        "agents",
        "The three agents",
        "Claude Code, Codex and Antigravity: sign-in, chats, and where your prompts go.",
        table(
            listOf("Agent", "Sign in with", "Chats kept in"),
            *Agent.entries.map { row("${it.displayName} (${it.maker})", it.signIn, it.chatsFolder) }.toTypedArray(),
        ),
        p(
            "Each is its maker's own VS Code extension, from its verified publisher account on the VS Code Marketplace. VS Code " +
                "keeps them updated. PocketIDE shows their own screens and never talks to the AI companies itself.",
        ),
        bullets(
            "Switch agents with ⋯ on the key bar, or tap an agent's icon at the top of the page.",
            "Enter makes a new line; Send on the key bar sends.",
            "Sign-in pages open in Chrome. If a page cannot come back to the computer by itself, the agent's screen shows how " +
                "to finish, often with a code to paste.",
            "Chats stay in the computer, so they are there on your next visit, and go when the computer is deleted.",
        ),
        warn(
            "$BEING_TESTED: Antigravity's Google sign-in from a cloud computer. If it does not finish, open the computer with " +
                "⋯ > Open in Chrome and sign in there.",
        ),
    )

    private val builds = section(
        "builds",
        "Builds and tests",
        "GitHub Actions builds apps for Android, iOS, macOS, Windows and the web.",
        p(
            "Agents build and test with GitHub Actions: workflow files in the repository run on GitHub's Linux, Windows and " +
                "macOS machines. Ask an agent, for example: \"Add a GitHub Actions workflow that builds the Android app and " +
                "uploads the APK.\"",
        ),
        bullets(
            "Builds of public repositories are free on GitHub's standard machines.",
            "Private repositories use your free minutes (2,000 a month on GitHub Free, as of ${Allowance.CHECKED_ON}). " +
                "Windows counts twice, macOS ten times.",
            "Logs and build files are kept for 90 days by default. Open a run on GitHub to download them.",
        ),
        warn(
            "Actions is for your project's builds and tests. GitHub's terms do not allow using it as a general-purpose " +
                "server, and PocketIDE never does.",
        ),
        link("Actions billing, by GitHub", DocLinks.ACTIONS_BILLING),
    )

    private val usage = section(
        DocsContent.USAGE_ID,
        "Hours and limits",
        "How the free hours are counted, and how to make them last.",
        p(
            "The Usage tab reads this month's report from GitHub. Hours count only while a computer runs, in core-hours: an " +
                "hour on 2 cores is 2 core-hours. Without a payment method, GitHub stops at the free allowance instead of charging.",
        ),
        table(
            listOf("Each month, as of ${Allowance.CHECKED_ON}", "GitHub Free", "GitHub Pro"),
            row("Codespaces compute", "120 core-hours (60 h on 2 cores)", "180 core-hours (90 h)"),
            row("Codespaces storage", "15 GB-months", "20 GB-months"),
            row("Actions, private repositories", "2,000 minutes", "3,000 minutes"),
        ),
        bullets(
            "Stop the computer when you are done: ⋯ > Stop computer, or Stop on the notification.",
            "Keep the idle time short, and the 2-core machine.",
            "Delete computers you no longer need; stopped ones still use storage.",
        ),
        p(
            "If Usage says GitHub does not share this account's usage, PocketIDE's GitHub App lacks the \"Plan\" permission, " +
                "or the account is not on GitHub's newer billing. GitHub's billing page always shows it.",
        ),
        link("GitHub billing", DocLinks.BILLING_USAGE),
        link("Codespaces billing, by GitHub", DocLinks.CODESPACES_BILLING),
    )

    private val safety = section(
        "safety",
        "Privacy and safety",
        "What keeps your code, account and phone safe, even from an agent's mistake.",
        bullets(
            "New projects are private repositories.",
            "Each cloud computer is its own virtual machine, kept apart from other people's; only you can open it.",
            "A computer's own GitHub token reaches only its repository. An agent there cannot touch your other repositories " +
                "or your account, and cannot delete or publish the repository.",
            "A push guard in each computer stops force pushes and deleting the default branch. Claude Code gets the same rule " +
                "as a policy it cannot change.",
            "Nothing from the cloud computer runs on your phone. The page cannot call into the app, pages other than GitHub " +
                "open in Chrome, and files reach it only when you pick them.",
            "PocketIDE has no server: no account of ours, no analytics, no ads, no tracking.",
            "Your GitHub sign-in on the phone is encrypted with a key in Android's Keystore. App lock and Hide from " +
                "screenshots add more.",
        ),
        info(
            "Inside its own computer an agent can change and delete files, and push new commits and branches. Review its " +
                "work before merging; GitHub keeps every commit that was pushed.",
        ),
        p(
            "The AI companies: what you ask an agent, and the code it reads, goes to its company under your account there. " +
                "Their policies say how long they keep it, and whether it trains their models; most let you turn that off.",
        ),
        link("Claude privacy settings", DocLinks.CLAUDE_PRIVACY),
        link("How Claude Code uses data", DocLinks.CLAUDE_DATA_USAGE),
        link("ChatGPT data controls", DocLinks.CHATGPT_DATA_CONTROLS),
        link("OpenAI data controls FAQ", DocLinks.OPENAI_DATA_FAQ),
        link("Antigravity settings", DocLinks.ANTIGRAVITY_SETTINGS),
        tip("Keep GitHub's Settings Sync off for Codespaces, so no other device changes your computer."),
        link("Codespaces settings on GitHub", DocLinks.CODESPACES_SETTINGS),
    )

    private val yourData = section(
        DocsContent.YOUR_DATA_ID,
        "Your data",
        "Where each piece is, who can see it, and how to delete it.",
        table(
            listOf("Data", "Where", "Delete"),
            row("Code", "Your GitHub repositories", "On GitHub"),
            row("Cloud computer and its files", "GitHub Codespaces", "Home > ⋯ > Delete computer; or GitHub, after the unused days"),
            row("Chats and agent sign-ins", "Inside each computer", "In each agent's history; or delete the computer"),
            row("Prompts sent to an AI company", "That company, under your account", "In your account there"),
            row("Build logs and files", "GitHub Actions", "On GitHub; they expire after 90 days by default"),
            row("Sign-in and settings", "This phone", "Settings > Your data > Delete from this phone; or uninstall"),
        ),
        p(
            "Leaving PocketIDE (Settings > Your data > Leave PocketIDE) deletes the phone's part and opens Android's page to " +
                "uninstall. Your repositories and computers stay in your GitHub account until you delete them there.",
        ),
        link("Your codespaces on GitHub", DocLinks.CODESPACES_LIST),
        link("PocketIDE's access on GitHub", DocLinks.GITHUB_AUTHORIZATIONS),
    )

    private val permissions = section(
        "permissions",
        "Permissions",
        "What PocketIDE may do on your phone, and why.",
        table(
            listOf("Android permission", "Why"),
            row("INTERNET", "To reach GitHub and your cloud computer"),
            row("ACCESS_NETWORK_STATE", "To tell being offline from being signed out"),
            row("POST_NOTIFICATIONS", "The \"Cloud computer is on\" notice with its Stop button; you can refuse it"),
            row("FOREGROUND_SERVICE", "To keep the connection open in the background, only while you want it"),
            row("FOREGROUND_SERVICE_SPECIAL_USE", "The kind of background work that connection is, as Android requires it named"),
            row("USE_BIOMETRIC", "App lock, with your phone's own screen lock"),
        ),
        p(
            "No storage, camera, microphone, location, contacts or accounts access. Files you attach in the computer are " +
                "picked with Android's own picker, one choice at a time.",
        ),
    )

    private val ownerSetUp = section(
        DocsContent.OWNER_SET_UP_ID,
        "Set up the GitHub App",
        "For whoever builds PocketIDE: the one GitHub App everyone signs in through.",
        steps(
            "On GitHub, open Settings > Developer settings > GitHub Apps > New GitHub App.",
            "Name it, give any homepage address, and tick \"Enable Device Flow\". Leave the callback and webhook empty.",
            "Repository permissions: Codespaces (read and write), Codespaces lifecycle admin (read and write), Codespaces " +
                "metadata (read), Contents (read and write), Administration (read and write, to make new repositories), " +
                "Actions (read), Metadata (read).",
            "Account permissions: Plan (read), for the Usage tab; and Repository creation, where GitHub shows it.",
            "Create the App. Copy its Client ID, and the name in its address (github.com/apps/name).",
            "Build PocketIDE with both (the secrets file lists them), or enter them in the app once.",
        ),
        info("The device flow needs no client secret, so none is in the app."),
        link("New GitHub App", DocLinks.GITHUB_NEW_APP),
    )

    val all: List<DocSection> = listOf(start, computer, agents, builds, usage, safety, yourData, permissions)
    val ownerOnly: List<DocSection> = listOf(ownerSetUp)
}
