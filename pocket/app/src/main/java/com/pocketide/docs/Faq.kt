package com.pocketide.docs

import com.pocketide.cloudshell.CloudShell

/** The questions owners ask, each answered in a few sentences and tied to its guide page. */
internal object Faq {
    val all: List<FaqEntry> = listOf(
        faq(
            "account",
            "start",
            "Do I need a PocketIDE account, or a credit card?",
            "No. PocketIDE has no account and no server. Cloud Shell needs only a Google account, with no card and no billing. " +
                "Each agent signs in to its own maker: a Claude, ChatGPT or Google account, or an API key.",
        ),
        faq(
            "free",
            DocsContent.COMPUTER_ID,
            "Is Cloud Shell really free? What are the limits?",
            "Yes, within Google's limits: 50 hours a week, at most 12 hours in one session, and it stops 40 minutes after you " +
                "stop using it. The 5 GB home folder is kept, until ${CloudShell.DELETED_AFTER_DAYS} days without use. Your " +
                "hours: in Cloud Shell, Session information > Usage quota.",
        ),
        faq(
            "background",
            DocsContent.COMPUTER_ID,
            "Do the agents keep working when I leave?",
            "For a while. PocketIDE disconnects 15 minutes after you leave the agents, and Google stops Cloud Shell about 40 " +
                "minutes after you stop using it; PocketIDE never keeps it awake, because that breaks Google's rules. For long " +
                "tasks, keep the agent open and look in now and then, or give it smaller steps.",
        ),
        faq(
            "phone",
            "start",
            "Does it heat the phone or drain the battery?",
            "No more than a web page: the agents and VS Code run in Google's cloud, and the phone only shows them. gcloud on " +
                "the phone only holds the connection, which uses little. The connection takes about 500 MB of storage.",
        ),
        faq(
            "in-app",
            DocsContent.IDE_ID,
            "Do the agents open inside the app, or in Chrome?",
            "Only inside PocketIDE: each agent's own VS Code, one thing at a time, full screen, with PocketIDE's bar (Back, " +
                "the agents, Reload, Tools) and the keys a phone keyboard lacks. Sign-in pages open in Chrome, never inside the " +
                "app, as Google and the AI companies require, and return to the agent by themselves.",
        ),
        faq(
            "gcloud",
            DocsContent.CONNECTION_ID,
            "Does gcloud run by itself? Is anything made in Google Cloud?",
            "gcloud installs and runs by itself: you only sign in with Google once (tap Allow on the \"Google Cloud SDK\" " +
                "page). No project, billing or OAuth client is made; only Cloud Shell's 50 free hours a week are used. " +
                "Everything runs in Cloud Shell; the phone keeps only gcloud, ssh and gcloud's sign-in, in PocketIDE's " +
                "private storage. To take the access back: Google Account > Security > Your connections to third-party apps " +
                "> Google Cloud SDK > Remove (or Computer > Sign gcloud out).",
        ),
        faq(
            "private-ports",
            DocsContent.CONNECTION_ID,
            "Can another app on my phone reach my Cloud Shell?",
            "No. Cloud Shell's ports arrive on the phone as socket files in PocketIDE's private storage, which no other app " +
                "can open, and gcloud's tunnel listens on such a file too. PocketIDE's own screens reach them through a door " +
                "on the phone's own address that answers only requests carrying its secret key. A sign-in's return port is " +
                "open only during that sign-in.",
        ),
        faq(
            "localhost-links",
            DocsContent.IDE_ID,
            "An agent shows a link to localhost. Can I open it?",
            "Yes: tap it. \"localhost\" there is Cloud Shell's own address, so PocketIDE opens it in its page viewer, through " +
                "its private door. Back goes to the page before; ✕ returns to the agent.",
        ),
        faq(
            "sign-in-localhost",
            "agents",
            "A sign-in ends at \"localhost refused to connect\".",
            "It should not: PocketIDE listens on that port during the sign-in and passes the return to the agent in Cloud " +
                "Shell. If it does (another app held the port, or Android closed PocketIDE meanwhile), open the agent in " +
                "PocketIDE and start the sign-in again.",
        ),
        faq(
            "more-extensions",
            "agents",
            "Can I add other extensions?",
            "Yes: in any agent's VS Code, Tools > Extensions finds everything on Open VSX, and Tools > Install " +
                "from a link installs one that is not there, from its maker's .vsix link. Each VS Code keeps its own; Open VSX's " +
                "update by themselves. Microsoft's own extensions (Pylance, C# Dev Kit, Remote, Live Share) are not on Open VSX.",
        ),
        faq(
            "to-github",
            "projects",
            "How do I put a project on GitHub?",
            "Open the Terminal (Tools > Terminal), run gh auth login once (GitHub's page opens in Chrome), then git push. " +
                "Or ask the agent to do it: it uses the same terminal.",
        ),
        faq(
            "account-safe",
            DocsContent.COMPUTER_ID,
            "Can this get my Google account in trouble?",
            "PocketIDE uses Cloud Shell as Google intends: through Google's own gcloud, unchanged in how it signs in and " +
                "talks to Google, only while you use it, and nothing keeps it awake. Each agent is told Cloud Shell's rules in " +
                "its own instructions (no mining, scanning, public tunnels or keep-awake jobs; heavy builds go to GitHub " +
                "Actions), and PocketIDE never turns off an agent's own approval settings. No one can promise zero risk: " +
                "Google decides, and what you ask an agent to do counts as yours. A separate Google account for " +
                "development keeps your main one, mail and photos apart.",
        ),
        faq(
            "which-account",
            "start",
            "Should I use my main Google account?",
            "A separate Google account just for development is recommended: the agents run code and commands in that " +
                "account's Cloud Shell, so a mistake, a leaked key or Google limiting Cloud Shell stays away from your main " +
                "Gmail, Drive and Photos. Your main account works too, the same way: Google's own sign-in, no password given to " +
                "PocketIDE. Either way, turn on 2-Step Verification.",
        ),
        faq(
            "chats-phone",
            "chats-usage",
            "Are my chats copied to the phone?",
            "No. Chats and Usage read them in Cloud Shell, over PocketIDE's private connection, each time you open them, " +
                "show them and keep nothing: once the page closes they are gone from the phone. They stay where each agent " +
                "keeps them in Cloud Shell (~/.claude, ~/.codex, ~/.gemini).",
        ),
        faq(
            "usage-limits",
            "chats-usage",
            "Why does Usage show tokens, and not how much of my plan is left?",
            "The plan's limits live at each AI company and need your sign-in there, which PocketIDE never reads. Usage " +
                "adds up the tokens each agent wrote in its own files, and its buttons open each company's own usage page; " +
                "/usage in Claude Code and /status in Codex show the limits too. Codex's show on Usage when Codex wrote them " +
                "in its files.",
        ),
        faq(
            "tools-back",
            DocsContent.IDE_ID,
            "How do Tools and Back work in an agent's VS Code?",
            "Tools (the wrench) opens the agent, a file, the terminal, settings, install from a link or all commands, each " +
                "full screen, one at a time. Back closes a menu, dialog or notice first, then what covers the agent, and leaves " +
                "the agent only on a second Back. An extension's own page (Antigravity's settings, for example) opens full " +
                "screen too, and Back returns to the agent.",
        ),
        faq(
            "connection-stays",
            DocsContent.CONNECTION_ID,
            "Does the connection delete itself after the set-up? Do the agents run on my phone?",
            "It stays, because gcloud on the phone opens every connection to Cloud Shell (about 500 MB, in PocketIDE's " +
                "private storage). Only gcloud and ssh run on the phone; the agents, VS Code and your projects run in Cloud " +
                "Shell. Computer > Remove the connection deletes it, and the set-up downloads it again when you next open PocketIDE.",
        ),
        faq(
            "gcloud-update",
            DocsContent.CONNECTION_ID,
            "What if Google changes gcloud?",
            "PocketIDE checks gcloud before it connects. If an update changed how it connects, PocketIDE undoes the update, " +
                "or puts back the gcloud this version was tested with, and holds gcloud's updates until the next PocketIDE. " +
                "Only if both fail does it ask you to update PocketIDE.",
        ),
        faq(
            "set-up-again",
            DocsContent.TROUBLE_ID,
            "PocketIDE asks for the set-up again. Why?",
            "It has not opened Cloud Shell for ${CloudShell.ASK_AGAIN_AFTER_DAYS} days, and Google deletes an unused home folder " +
                "after ${CloudShell.DELETED_AFTER_DAYS}. Connect once: PocketIDE sets up again only what is missing.",
        ),
        faq(
            "old-version",
            DocsContent.YOUR_DATA_ID,
            "I used an earlier PocketIDE. What happens to it?",
            "Version 7 opens Cloud Shell's agents inside the app; version 6's Cloud Shell set-up is updated by itself at the " +
                "first connection. PocketIDE 5 kept a computer on the phone: it stays until you delete it in Settings > Your " +
                "data, where you can first save its projects and chats as a zip. Codespaces you made with version 4 stay in " +
                "your GitHub account until you delete them there.",
        ),
    )
}
