package com.pocketide.docs

import com.pocketide.agents.Agent
import com.pocketide.cloudshell.CloudShell

/** The guide: short pages in reading order. Facts that change carry the day they were checked. */
internal object Guide {
    private val start = section(
        "start",
        "Start here",
        "What PocketIDE is, and the steps to your first agent.",
        p(
            "PocketIDE gives your phone a computer in Google's cloud: Google Cloud Shell, free with a Google account. The " +
                "official agents from Anthropic, OpenAI and Google each work there in their own VS Code, which opens right " +
                "inside PocketIDE. The phone only shows the page, so it stays cool.",
        ),
        steps(
            "Pick your Google account in Android's own chooser. A separate Google account for development keeps your main " +
                "one apart; your main one works too.",
            "Set up PocketIDE's connection: one tap, about 130 MB, once.",
            "Sign in to gcloud: Google's page opens in Chrome; pick your account and tap Allow.",
            "Set up Cloud Shell: one tap. PocketIDE starts it and installs VS Code and the agents there (about 5 minutes, once).",
            "On Home, tap Claude Code, Codex or Antigravity: its own VS Code opens in PocketIDE. Sign in to the agent once.",
        ),
        table(
            listOf("What", "Where"),
            row("Your projects", "In Cloud Shell: ~/projects, a folder for each agent"),
            row("Chats and agent sign-ins", "In Cloud Shell, kept by each agent"),
            row("PocketIDE's connection (gcloud and its sign-in)", "On this phone, in PocketIDE's private storage"),
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
            "Cloud Shell is a Linux computer (x86-64) that Google runs for each Google account, free. PocketIDE reaches it " +
                "through Google's own gcloud on your phone (see PocketIDE's connection), or, the Chrome way, in Chrome.",
        ),
        table(
            listOf("Free limit (Google, checked ${DocLinks.CHECKED_ON})", "Value"),
            row("Hours", "50 a week (about 7 a day), at most 12 in one session"),
            row("When you leave", "It stops after about 40 minutes without use"),
            row("Home folder", "5 GB, the only part kept; the set-up uses about 1.6 GB"),
            row("Not used", "Google deletes the home folder after ${CloudShell.DELETED_AFTER_DAYS} days, and emails you first"),
        ),
        bullets(
            "The set-up is one script, pinned in this app version and checked by its SHA-256 before it runs: code-server " +
                "(VS Code for the web), checked against its pinned SHA-256, and each agent from Open VSX, checked against the " +
                "SHA-256 Open VSX publishes. PocketIDE runs it through its connection; the Chrome way pastes it in Cloud Shell.",
            "Each agent gets its own VS Code, with its own port (Claude Code ${CloudShell.port(Agent.CLAUDE)}, Codex " +
                "${CloudShell.port(Agent.CODEX)}, Antigravity ${CloudShell.port(Agent.ANTIGRAVITY)}), settings, extensions and " +
                "projects folder. It listens only inside Cloud Shell.",
            "Whenever Cloud Shell starts, it starts the agents' VS Code by itself, tidies old caches and logs, and once a day " +
                "installs newer agent and code-server releases. Running work and your projects are never touched.",
            "After an app update brings a newer set-up, PocketIDE runs it by itself at the next connection. After " +
                "${CloudShell.ASK_AGAIN_AFTER_DAYS} days without opening Cloud Shell, it asks you to connect once, before Google " +
                "may delete the home folder: only what is missing is set up again.",
        ),
        warn(
            "Use Cloud Shell yourself, while you work, as Google intends: no coin mining, network scanning or tricks to keep " +
                "it awake, and never share a Web Preview link. Google can turn Cloud Shell off for an account that breaks its rules.",
        ),
        link("Open the Computer tab", "app:computer"),
        link("Google's limits", CloudShell.LIMITS),
    )

    private val connection = section(
        DocsContent.CONNECTION_ID,
        "PocketIDE's connection (gcloud)",
        "How PocketIDE reaches Cloud Shell without a Google Cloud project, what it keeps, and how to remove it.",
        p(
            "PocketIDE runs Google's own command-line tool, gcloud, on your phone, unchanged, in PocketIDE's private storage " +
                "(with a small Ubuntu under PRoot: no root, no virtual machine). gcloud signs in with Google, starts Cloud " +
                "Shell when it is off, and opens one encrypted SSH connection to it through Google's own servers.",
        ),
        table(
            listOf("Question", "Answer"),
            row(
                "Does gcloud set up and run by itself?",
                "Yes. PocketIDE downloads it as soon as the set-up page opens (Google's own release, checked by its SHA-256), " +
                    "sets it up, runs it and updates it once a day with gcloud's own updater. You do not type any command.",
            ),
            row(
                "What if a gcloud update breaks the connection?",
                "PocketIDE checks gcloud before it connects. When an update changed how gcloud connects, PocketIDE undoes it " +
                    "(gcloud's own restore) or puts back the version this PocketIDE was tested with, and gcloud's updates wait " +
                    "for the next PocketIDE. Only if both fail does it ask you to update PocketIDE; the Chrome way still works.",
            ),
            row(
                "Does the connection delete itself after the set-up?",
                "No: gcloud on the phone opens every connection, so it stays (about 500 MB). Only gcloud and ssh run on the " +
                    "phone, never the agents. Computer > Remove the connection deletes it; the agents then open in Chrome.",
            ),
            row(
                "What do I do once?",
                "Sign in with Google once: Google's page opens in Chrome; pick your account and tap Allow on \"Google Cloud " +
                    "SDK wants to access your Google Account\".",
            ),
            row(
                "Is a Google Cloud project made?",
                "No. No project, no billing, no OAuth client and no keys of PocketIDE's own are made. Only Cloud Shell's free " +
                    "hours (50 a week) are used.",
            ),
            row(
                "Where does everything run?",
                "In Cloud Shell: VS Code, the agents, your projects and chats. The phone keeps only gcloud, ssh and gcloud's " +
                    "sign-in, in PocketIDE's private storage, which no other app can read.",
            ),
            row(
                "How do I take the access back?",
                "Computer > Sign gcloud out (Google ends the sign-in too), or in your Google Account: Security > Your " +
                    "connections to third-party apps > Google Cloud SDK > Remove.",
            ),
        ),
        bullets(
            "Ports stay private. VS Code and the agents listen only inside Cloud Shell. On the phone, each port arrives as a " +
                "socket file in PocketIDE's private storage, which no other app can open, and gcloud's own tunnel listens on " +
                "such a file too (PocketIDE's one change to how gcloud runs). PocketIDE's screens reach them through its " +
                "private door on this phone's own address, which answers only requests carrying its secret key.",
            "A sign-in page (Claude Code's, Codex's, Antigravity's Google page) returns to localhost on the phone: PocketIDE " +
                "listens there only during that sign-in and passes the return on to the agent in Cloud Shell, unchanged.",
            "PocketIDE connects while you use the agents, and disconnects 15 minutes after you leave them; Cloud Shell then " +
                "stops by itself, as when you close its page. Nothing keeps it awake. While connected, a notice with a " +
                "Disconnect button says so.",
            "Size on the phone: about 130 MB to download, about 500 MB set up. Computer > Remove the connection deletes it " +
                "all (your Cloud Shell stays).",
        ),
        link("Google Account: third-party connections", "https://myaccount.google.com/connections"),
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
            "Each agent is its maker's own VS Code extension, from its verified publisher on Open VSX, in its own VS Code in " +
                "your Cloud Shell. PocketIDE never talks to the AI companies itself.",
        ),
        steps(
            "Claude Code: in its panel, Sign in. Chrome opens: sign in, and the page returns to Claude Code by itself (or " +
                "copy the code the page shows and paste it where Claude Code asks).",
            "Codex: Sign in with ChatGPT, and sign in. The page returns to Codex by itself.",
            "Antigravity: Continue with Google, and sign in with Google in Chrome. The page returns to Antigravity by itself.",
            "The Chrome way only: a sign-in page that ends at \"localhost refused to connect\": tap PocketIDE's tools button " +
                "at the top of that page (in Chrome's own app: ⋮ > Share > PocketIDE (Finish sign-in)).",
        ),
        p(
            "More extensions: Tools (the wrench in PocketIDE's bar) > Extensions finds any extension on Open VSX, and Tools > " +
                "Install from a link installs one Open VSX does not have, from its maker's .vsix link. Each VS Code keeps its " +
                "own, and Open VSX's update by themselves. Microsoft's own extensions are not on Open VSX (Microsoft allows " +
                "them only in its products).",
        ),
        link("Claude Code's guide", Agent.CLAUDE.docsUrl),
        link("Codex's guide", Agent.CODEX.docsUrl),
        link("Antigravity's guide", Agent.ANTIGRAVITY.docsUrl),
    )

    private val ide = section(
        DocsContent.IDE_ID,
        "Working in VS Code",
        "PocketIDE's bar and keys, links to localhost, and typing on a phone.",
        table(
            listOf("In PocketIDE's bar", "What it does"),
            row("Back (←), and the phone's Back", "Closes a menu or dialog, then a file over the agent; home only on a second Back"),
            row("The three logos", "Switch agents; each keeps its page, so nothing loads again"),
            row("Reload", "Loads this VS Code again"),
            row("Tools (the wrench)", "The agent, terminal, files, search, Git, extensions, install from a link, settings, all commands"),
            row("⋮", "Open in Chrome instead, Cloud Shell's terminal and files, PocketIDE's home, Disconnect"),
        ),
        bullets(
            "Above the keyboard: Paste, Esc, Tab, Ctrl+C, the arrows and Enter, for the terminal and the editor.",
            "A link to localhost in an agent's chat or the terminal (a dev server, a preview) opens in PocketIDE's page " +
                "viewer, through the private door: its Back goes to the page before, its ✕ back to the agent.",
            "Other web pages and sign-ins open in Chrome; Chrome's back arrow returns to the agent.",
            "Claude Code and Codex send with their Send button: Enter makes a new line, as a phone keyboard expects. Turning " +
                "the phone sideways gives VS Code more room.",
            "Uploading: VS Code's Upload uses Android's own picker. Downloading: use Cloud Shell's Files page (⋮ > Cloud " +
                "Shell's files), or git.",
        ),
    )

    private val projects = section(
        "projects",
        "Projects and GitHub",
        "Where your work is, and how to put it on GitHub.",
        bullets(
            "Each agent opens its own folder: ${Agent.entries.joinToString(", ") { CloudShell.projects(it) }}.",
            "To work on a project from GitHub, open the Terminal (Tools > Terminal) and run git clone with its address.",
            "Keep a copy elsewhere: run gh auth login once (GitHub's page opens in Chrome), then git push. Cloud Shell's home " +
                "folder is deleted after ${CloudShell.DELETED_AFTER_DAYS} days without use.",
            "Cloud Shell's own page (⋮ > Cloud Shell's files) has Upload and Download for single files.",
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
            row("Connecting takes long", "After a break Cloud Shell starts again first: up to a minute or two."),
            row("\"Sign in to gcloud again\"", "Tap Sign in to gcloud; Google's page opens in Chrome. Allow, and it connects."),
            row("\"Accept Google Cloud's terms\" or \"verify your account\"", "Tap Open Cloud Shell in Chrome, do what Google asks once, then Try again."),
            row("The connection dropped", "It connects again by itself while an agent is open; else tap Try again."),
            row("VS Code says it cannot reconnect", "Tap Reload in PocketIDE's bar."),
            row("An agent is missing", "Tools > Terminal, run pocketide update."),
            row("Nothing helps", "⋮ > Open in Chrome instead: the agents open in Chrome, as in PocketIDE 6."),
            row("Cloud Shell says the weekly quota is used", "It comes back the next week; Cloud Shell's page shows the usage."),
            row("The home folder is full", "Delete old projects or files; the first set-up needs about 2 GB free."),
        ),
        link("PocketIDE's connection", "help:${DocsContent.CONNECTION_ID}"),
    )

    private val yourData = section(
        DocsContent.YOUR_DATA_ID,
        "Your data",
        "What is where, who can see it, and how to delete it.",
        bullets(
            "In your Cloud Shell, which only your Google account opens: your projects, each agent's chats and sign-in, and each " +
                "agent's VS Code. Google's privacy notice for Google Cloud applies. It is not in Drive or Photos.",
            "On this phone: PocketIDE's settings, and its connection in PocketIDE's private storage: Ubuntu with Google's " +
                "gcloud and gcloud's sign-in, which no other app can read. Android's backup copies none of it.",
            "At the AI companies: what you ask an agent, and the code it reads, under your account there.",
            "Downloads: on the phone, Ubuntu (from Ubuntu's servers) and gcloud (from Google), each checked by its SHA-256; " +
                "in Cloud Shell, the script and code-server from GitHub and the agents from Open VSX.",
            "Delete: in Cloud Shell, run sudo rm -rf \$HOME, then restart it. On the phone: Settings > Your data (it signs " +
                "gcloud out first).",
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
            row("INTERNET", "gcloud's connection to Cloud Shell, its downloads and updates, and each agent's icon"),
            row("ACCESS_NETWORK_STATE", "Telling Linux the phone's DNS servers when the network changes (Wi-Fi to mobile data)"),
            row("FOREGROUND_SERVICE", "Keeping the connection open while you finish a sign-in in Chrome or switch apps for a moment"),
            row("FOREGROUND_SERVICE_SPECIAL_USE", "The kind of that service Android 14 asks apps to name: the owner's own connection"),
            row("POST_NOTIFICATIONS", "The notice that says PocketIDE is connected, with its Disconnect button (Android asks you)"),
            row("USE_BIOMETRIC", "App lock, with your phone's own screen lock"),
        ),
        p(
            "No storage, camera, microphone, location, contacts or accounts permission. The Google account comes from " +
                "Android's own account chooser, which gives PocketIDE only the one you pick. Google's sign-in opens in Chrome, " +
                "never inside PocketIDE.",
        ),
    )

    /** The guide, in reading order. */
    val all: List<DocSection> = listOf(start, computer, connection, agents, ide, projects, trouble, yourData, permissions)
}
