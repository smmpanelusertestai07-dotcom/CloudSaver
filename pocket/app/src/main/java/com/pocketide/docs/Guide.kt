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
            "Pick your Google account in Android's own chooser. A separate Google account for development is recommended: " +
                "the agents run code in its Cloud Shell, so a mistake or a leaked key stays away from your main Gmail, Drive " +
                "and Photos. Your main account works too, the same way.",
            "PocketIDE's connection downloads by itself as soon as the set-up opens: about 130 MB, once.",
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
                "through Google's own gcloud on your phone (see PocketIDE's connection), and the agents open only inside PocketIDE.",
        ),
        table(
            listOf("Free limit (Google, checked ${DocLinks.CHECKED_ON})", "Value"),
            row("Hours", "50 a week (about 7 a day), at most 12 in one session"),
            row("When you leave", "It stops after about 40 minutes without use"),
            row("Home folder", "5 GB, the only part kept; the set-up uses about 1.9 GB"),
            row("Not used", "Google deletes the home folder after ${CloudShell.DELETED_AFTER_DAYS} days, and emails you first"),
        ),
        bullets(
            "The set-up is one script, pinned in this app version and checked by its SHA-256 before it runs; it checks " +
                "code-server (VS Code for the web) and each agent from Open VSX against their published SHA-256 too.",
            "Each agent gets its own VS Code, with its own port (Claude Code ${CloudShell.port(Agent.CLAUDE)}, Codex " +
                "${CloudShell.port(Agent.CODEX)}, Antigravity ${CloudShell.port(Agent.ANTIGRAVITY)}), settings, extensions and " +
                "projects folder. It listens only inside Cloud Shell.",
            "Each agent's VS Code starts when you open that agent, so Cloud Shell's memory goes to the agents you use; Usage " +
                "stops one you no longer need. Whenever Cloud Shell starts, it tidies old caches and logs, " +
                "and once a day installs newer agent and code-server releases. Running work and your projects are never touched.",
            "A newer set-up (after an app update) runs by itself at the next connection. After " +
                "${CloudShell.ASK_AGAIN_AFTER_DAYS} days unused, PocketIDE asks you to connect once, before Google may delete " +
                "the home folder.",
        ),
        warn(
            "Use Cloud Shell yourself, while you work, as Google intends: no coin mining, network scanning or tricks to keep " +
                "it awake, and no public tunnels. Google can turn Cloud Shell off for an account that breaks its rules.",
        ),
        link("Open the Computer tab", "app:computer"),
        link("Google's limits", CloudShell.LIMITS),
    )

    private val connection = section(
        DocsContent.CONNECTION_ID,
        "PocketIDE's connection (gcloud)",
        "How PocketIDE reaches Cloud Shell without a Google Cloud project, what it keeps, and how to remove it.",
        p(
            "PocketIDE runs Google's own gcloud, unchanged, on your phone in its private storage (a small Ubuntu under PRoot: " +
                "no root, no virtual machine). gcloud signs in with Google, starts Cloud Shell when it is off, and opens one " +
                "encrypted SSH connection to it through Google's servers.",
        ),
        table(
            listOf("Question", "Answer"),
            row(
                "Does gcloud set up and run by itself?",
                "Yes: downloaded when the set-up page opens (checked by its SHA-256), then updated daily by gcloud's own " +
                    "updater. You sign in once and type no command.",
            ),
            row(
                "What if a gcloud update breaks the connection?",
                "PocketIDE undoes it, or puts back the gcloud it was tested with, until the next PocketIDE.",
            ),
            row(
                "Is a Google Cloud project made?",
                "No project, billing or key of PocketIDE's own; only Cloud Shell's free hours.",
            ),
            row(
                "How do I take the access back?",
                "Computer > Sign gcloud out (Google ends the sign-in too), or in your Google Account: Security > Your " +
                    "connections to third-party apps > Google Cloud SDK > Remove.",
            ),
        ),
        bullets(
            "Ports stay private: VS Code, the agents and the browser listen only inside Cloud Shell; on the phone each is " +
                "a socket file only PocketIDE can open (gcloud's tunnel too), reached through PocketIDE's private door, which " +
                "answers only requests carrying its secret key.",
            "A sign-in's return to localhost on the phone goes to the agent in Cloud Shell, unchanged; PocketIDE listens " +
                "only during that sign-in.",
            "PocketIDE connects while you use the agents and disconnects 15 minutes after you leave them; Cloud Shell then " +
                "stops by itself. Nothing keeps it awake; while connected, a notice with Disconnect says so.",
            "Size: 130 MB to download, 500 MB set up; Computer > Remove the connection deletes it (your Cloud Shell stays).",
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
            "A sign-in page that ends at \"localhost refused to connect\" (another app held the port, or Android closed " +
                "PocketIDE meanwhile): open the agent in PocketIDE and start the sign-in again.",
        ),
        p(
            "More extensions: Tools > All commands > Install Extensions (Open VSX), or Tools > Install from a link (a maker's " +
                ".vsix). Microsoft's own extensions are not on Open VSX.",
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
            row("Back (←), and the phone's Back", "Closes a menu, dialog or notice; then what covers the agent; home only on a second Back"),
            row("The three logos", "Switch agents; each keeps its page, so nothing loads again"),
            row("Reload", "Starts this VS Code again if it stopped, and loads it again"),
            row("Tools (the wrench)", "The agent, open a file, terminal, settings, install from a link, all commands, the browser"),
            row("⋮", "PocketIDE's home, Disconnect"),
        ),
        bullets(
            "One thing at a time, full screen: the agent, or what covers it (a file, a diff, settings, an extension's own page, " +
                "a terminal). Back returns to the agent; a terminal keeps running behind it.",
            "Dialogs, notices, menus and the command palette always fit the screen; a notice with many buttons scrolls sideways.",
            "Above the keyboard: Paste, Esc, Tab, Ctrl+C, the arrows and Enter, for the terminal and the editor.",
            "A link to localhost in an agent's chat or the terminal (a dev server, a preview) opens in PocketIDE's page " +
                "viewer, through the private door: its Back goes to the page before, its ✕ back to the agent.",
            "Other web pages and sign-ins open in Chrome (a page that opens without your tap asks first); Chrome's back arrow " +
                "returns to the agent.",
            "Claude Code and Codex send with their Send button; Enter makes a new line.",
            "Uploading: VS Code's Upload uses Android's own picker. A file to keep: put it on GitHub (git push).",
        ),
    )

    private val browser = section(
        "browser",
        "The browser",
        "The Chrome the agents use in Cloud Shell: watch it live, or take over.",
        bullets(
            "Tools > Browser starts Google's Chrome for Testing in Cloud Shell (the set-up installs it, updates keep it " +
                "current) and shows it live, as an agent uses it.",
            "Tap, scroll or type to take over; Watch only keeps your taps out, and Phone size shows a page as a phone would.",
            "Agents drive it through Chrome's DevTools on Cloud Shell's 127.0.0.1:${CloudShell.BROWSER_DEVTOOLS_PORT} " +
                "(Playwright's connectOverCDP); their instructions say how.",
            "Short of memory, PocketIDE offers to stop another agent's VS Code. It stops after 20 minutes unused; Usage stops it at once.",
            "Agents can read what it shows, signed-in pages included: sign in there only where you are happy for them to see.",
        ),
    )

    private val chatsUsage = section(
        "chats-usage",
        "Chats and Usage",
        "The agents' chats and the numbers that count, live from Cloud Shell.",
        bullets(
            "Chats: each agent's chats, newest first; tap one to read it, with the commands and files used. Open goes to " +
                "the agent; Delete removes a Claude Code or Codex chat from Cloud Shell for good. Antigravity's show their " +
                "title and plain-text files; delete those in Antigravity.",
            "Usage: this session against Google's 12 hours, memory, the home folder, the week's hours against Google's 50 " +
                "(PocketIDE's count), each agent's tokens, and Stop for a VS Code or the browser. The plans' limits: each " +
                "company's page (the buttons), /usage in Claude Code, /status in Codex.",
            "Both read only while connected (Connect starts Cloud Shell) and keep nothing on the phone.",
        ),
        link("Google's Cloud Shell limits", DocLinks.CLOUD_SHELL_QUOTA),
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
            row("\"Accept Google Cloud's terms\" or \"verify your account\"", "Tap Open Google's page (once), do what Google asks, then Try again."),
            row("The connection dropped", "It connects again by itself while an agent is open; else tap Try again."),
            row("VS Code says it cannot reconnect", "Tap Reload in PocketIDE's bar: it starts VS Code again if it stopped."),
            row("A VS Code or the browser needs memory", "Usage: stop an agent's VS Code you are not using, then try again."),
            row("An agent is missing", "Tools > Terminal, run pocketide update."),
            row("Nothing helps", "Disconnect, connect again; then Computer > Run the set-up again (your projects and chats stay)."),
            row("Cloud Shell says the weekly quota is used", "It comes back the next week; Cloud Shell's page shows the usage."),
            row("The home folder is full", "Delete old projects or files; the first set-up needs about 2.5 GB free."),
        ),
        link("PocketIDE's connection", "help:${DocsContent.CONNECTION_ID}"),
    )

    private val yourData = section(
        DocsContent.YOUR_DATA_ID,
        "Your data",
        "What is where, who can see it, and how to delete it.",
        bullets(
            "In your Cloud Shell, which only your Google account opens: your projects, each agent's chats and sign-in, each " +
                "agent's VS Code, and the browser's profile (~/.pocketide/browser). Google's privacy notice for Google Cloud " +
                "applies. It is not in Drive or Photos.",
            "On this phone: PocketIDE's settings, and its connection in PocketIDE's private storage: Ubuntu with Google's " +
                "gcloud and gcloud's sign-in, which no other app can read. Android's backup copies none of it.",
            "At the AI companies: what you ask an agent, and the code it reads, under your account there.",
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
    val all: List<DocSection> = listOf(start, computer, connection, agents, ide, browser, chatsUsage, projects, trouble, yourData, permissions)
}
