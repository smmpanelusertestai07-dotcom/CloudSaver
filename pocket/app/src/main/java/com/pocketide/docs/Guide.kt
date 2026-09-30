package com.pocketide.docs

import com.pocketide.agents.Agent
import com.pocketide.cloudshell.CloudShell

/** The guide: short pages in reading order. Facts that change carry the day they were checked. */
internal object Guide {
    private val start = section(
        "start",
        "Start here",
        "What PocketIDE is, and the three steps to your first agent.",
        p(
            "PocketIDE gives your phone a computer in Google's cloud: Google Cloud Shell, free with a Google account. The " +
                "official agents from Anthropic, OpenAI and Google each work there in their own VS Code, and the phone only " +
                "shows the page, so it stays cool.",
        ),
        steps(
            "Pick your Google account in Android's own chooser. A separate Google account for development keeps your main " +
                "one apart; your main one works too.",
            "Copy the set-up command, open Cloud Shell, paste it and press Enter. About 5 minutes, once.",
            "On Home, tap Claude Code, Codex or Antigravity: its own VS Code opens in Chrome. Sign in to the agent once.",
        ),
        table(
            listOf("What", "Where"),
            row("Your projects", "In Cloud Shell: ~/projects, a folder for each agent"),
            row("Chats and agent sign-ins", "In Cloud Shell, kept by each agent"),
            row("PocketIDE's settings", "On this phone"),
            row("PocketIDE's own server", "None: there is no PocketIDE account"),
        ),
        link("Open the Computer tab", "app:computer"),
    )

    private val computer = section(
        DocsContent.COMPUTER_ID,
        "Your computer: Google Cloud Shell",
        "Google's free Linux computer: set-up, limits, starting, updates, and putting it right.",
        p(
            "Cloud Shell is a Linux computer (x86-64) that Google runs for each Google account, free. PocketIDE opens it in " +
                "Chrome with the account you picked, because Google allows its sign-in only in a real browser.",
        ),
        table(
            listOf("Free limit (Google, checked ${DocLinks.CHECKED_ON})", "Value"),
            row("Hours", "50 a week (about 7 a day), at most 12 in one session"),
            row("When you leave", "It stops after 40 minutes without use"),
            row("Home folder", "5 GB, the only part kept; the set-up uses about 1.6 GB"),
            row("Not used", "Google deletes the home folder after ${CloudShell.DELETED_AFTER_DAYS} days, and emails you first"),
        ),
        bullets(
            "The set-up is one command. It downloads a script pinned in this app version, checks its SHA-256, and runs it: " +
                "code-server (VS Code for the web), checked against its pinned SHA-256, and each agent from Open VSX, checked " +
                "against the SHA-256 Open VSX publishes.",
            "Each agent gets its own VS Code, with its own port (Claude Code ${CloudShell.port(Agent.CLAUDE)}, Codex " +
                "${CloudShell.port(Agent.CODEX)}, Antigravity ${CloudShell.port(Agent.ANTIGRAVITY)}), settings, extensions and " +
                "projects folder, reached through Cloud Shell's Web Preview, which only your account can open.",
            "Whenever Cloud Shell starts, it starts the agents' VS Code by itself, tidies old caches and logs, and once a day " +
                "installs newer agent releases. Running work and your projects are never touched.",
            "PocketIDE asks for the set-up again after ${CloudShell.ASK_AGAIN_AFTER_DAYS} days without opening Cloud Shell, " +
                "before Google may delete it. Computer > Run the set-up again does it at any time: it only adds what is missing.",
        ),
        warn(
            "Use Cloud Shell yourself, while you work, as Google intends: no coin mining, network scanning or tricks to keep " +
                "it awake, and never share a Web Preview link. Google can turn Cloud Shell off for an account that breaks its rules.",
        ),
        link("Open the Computer tab", "app:computer"),
        link("Google's limits", CloudShell.LIMITS),
    )

    private val agents = section(
        "agents",
        "The agents",
        "Claude Code, Codex and Antigravity: signing in, chats, and more extensions.",
        table(
            listOf("Agent", "Sign in with", "Chats and sign-in in"),
            *Agent.entries.map { row("${it.displayName} (${it.maker})", it.signIn, it.chatsFolder) }.toTypedArray(),
        ),
        p(
            "Claude Code and Codex are their makers' own VS Code extensions, from their verified publishers on Open VSX, each " +
                "in its own VS Code in your Cloud Shell. Antigravity is Google's own Antigravity screen, from Google's own " +
                "program (agy) in your Cloud Shell. PocketIDE never talks to the AI companies itself.",
        ),
        steps(
            "Claude Code: in its panel, Sign in. Chrome opens: sign in, copy the code the page shows, come back and paste it.",
            "Codex: Sign in with ChatGPT, and sign in. PocketIDE brings the sign-in back to Cloud Shell by itself.",
            "Antigravity: Continue with Google, then the blue bar Continue signing in with Google, and sign in. PocketIDE " +
                "brings the sign-in back to Cloud Shell by itself.",
            "A sign-in page that ends at \"localhost refused to connect\": tap PocketIDE's tools button at the top of that " +
                "page (in Chrome's own app: ⋮ > Share > PocketIDE (Finish sign-in)). The agent is signed in.",
        ),
        p(
            "More extensions: in Claude Code's or Codex's VS Code, Tools (bottom left) > Extensions finds any extension on " +
                "Open VSX, and Tools > Install from a link installs one Open VSX does not have, from its maker's .vsix link. " +
                "Each VS Code keeps its own, and Open VSX's update by themselves. Microsoft's own extensions are not on Open " +
                "VSX (Microsoft allows them only in its products).",
        ),
        link("Claude Code's guide", Agent.CLAUDE.docsUrl),
        link("Codex's guide", Agent.CODEX.docsUrl),
        link("Antigravity's guide", Agent.ANTIGRAVITY.docsUrl),
    )

    private val ide = section(
        DocsContent.IDE_ID,
        "Working in VS Code",
        "The Chrome tab, switching agents, and typing on a phone.",
        table(
            listOf("In the Chrome tab", "What it does"),
            row("The arrow (top left)", "Back to PocketIDE"),
            row(
                "The tools button",
                "Every agent with its logo, the terminal, the files, how each agent signs in; on a sign-in page that ended " +
                    "at localhost, it finishes the sign-in",
            ),
            row("Chrome's menu (⋮)", "The other agents, the terminal and the files"),
            row("Back", "The page before, then PocketIDE"),
        ),
        bullets(
            "Each agent opens full screen: Claude Code and Codex in VS Code's side bar, maximized, and Antigravity as Google's " +
                "own Antigravity screen.",
            "In Claude Code's and Codex's VS Code, Tools (bottom left) opens the agent, the terminal, the files, search, Git, " +
                "extensions and settings; the keyboard button next to it shows Esc, Tab, the arrows, Enter and Ctrl+C for the " +
                "terminal, and hides them again.",
            "Claude Code and Codex send with their Send button: Enter makes a new line, as a phone keyboard expects. Turning " +
                "the phone sideways gives VS Code more room.",
            "If VS Code does not open, Cloud Shell is probably stopped: tap Start Cloud Shell, wait for the terminal's prompt, " +
                "then open the agent again.",
        ),
    )

    private val projects = section(
        "projects",
        "Projects and GitHub",
        "Where your work is, and how to put it on GitHub.",
        bullets(
            "Each agent opens its own folder: ${Agent.entries.joinToString(", ") { CloudShell.projects(it) }}.",
            "To work on a project from GitHub, open the Terminal in the agent's VS Code and run git clone with its address.",
            "Keep a copy elsewhere: run gh auth login once (GitHub's page opens in Chrome), then git push. Cloud Shell's home " +
                "folder is deleted after ${CloudShell.DELETED_AFTER_DAYS} days without use.",
            "Cloud Shell's ⋮ menu has Upload and Download for single files.",
            "Android apps: an emulator cannot run in Cloud Shell, and the Android tools take much of the 5 GB home folder. " +
                "Build them on GitHub Actions (an agent can write the workflow).",
        ),
    )

    private val trouble = section(
        DocsContent.TROUBLE_ID,
        "When something does not work",
        "The usual fixes, lightest first.",
        table(
            listOf("What you see", "Try"),
            row("VS Code does not open", "Start Cloud Shell, wait for the prompt, open the agent again."),
            row("Could not connect to port 8080 (or 8081, 8082)", "In the Terminal, run pocketide. Still nothing: Computer > Run the set-up again."),
            row("An agent is missing", "In the Terminal, run pocketide update."),
            row("A sign-in ends at \"localhost refused to connect\"", "Tap PocketIDE's tools button at the top of that page (The agents)."),
            row("Antigravity says it is starting", "Wait a few seconds; still there: in the Terminal, run pocketide update."),
            row("Chrome offers to turn on sync", "Tap No thanks: it is not needed."),
            row("Cloud Shell says the weekly quota is used", "It comes back the next week; Session information > Usage quota shows it."),
            row("The home folder is full", "Delete old projects or files; the first set-up needs about 2 GB free."),
        ),
        link("The agents", "help:agents"),
    )

    private val yourData = section(
        DocsContent.YOUR_DATA_ID,
        "Your data",
        "What is where, who can see it, and how to delete it.",
        bullets(
            "In your Cloud Shell, which only your Google account opens: your projects, each agent's chats and sign-in, and each " +
                "agent's VS Code. Google's privacy notice for Google Cloud applies. It is not in Drive or Photos.",
            "On this phone: only PocketIDE's settings (the theme, App lock, the Google account Cloud Shell opens with, and " +
                "when it was set up and last opened). Android's backup does not copy them.",
            "At the AI companies: what you ask an agent, and the code it reads, under your account there.",
            "Downloads the set-up makes in Cloud Shell: the script and code-server from GitHub, the agents from Open VSX, and " +
                "Antigravity's own tool from Google.",
            "Delete: in Cloud Shell, run sudo rm -rf \$HOME, then ⋮ > Restart. On the phone: Settings > Your data.",
        ),
        link("Open Your data", "app:data"),
        link("Google Cloud privacy notice", CloudShell.PRIVACY),
    )

    private val permissions = section(
        "permissions",
        "Permissions",
        "What PocketIDE may do on your phone, and why.",
        table(
            listOf("Android permission", "Why"),
            row("INTERNET", "To show each agent's icon, from Open VSX"),
            row("USE_BIOMETRIC", "App lock, with your phone's own screen lock"),
        ),
        p(
            "No storage, camera, microphone, location, contacts, notifications or accounts permission. The Google account " +
                "comes from Android's own account chooser, which gives PocketIDE only the one you pick. Cloud Shell opens in " +
                "Chrome, with Chrome's own sign-in.",
        ),
    )

    /** The guide, in reading order. */
    val all: List<DocSection> = listOf(start, computer, agents, ide, projects, trouble, yourData, permissions)
}
