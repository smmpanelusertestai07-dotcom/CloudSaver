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
            "Inside PocketIDE: each agent's own VS Code, with PocketIDE's bar (Back, the agents, Reload, Tools) and the keys " +
                "a phone keyboard lacks. It is drawn by Android's WebView, with no address bar and no Chrome menu. Google's " +
                "own sign-in pages always open in Chrome, never inside the app, as Google requires. The Chrome way (⋮ > Open " +
                "in Chrome instead) still works.",
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
            "Inside PocketIDE this should not happen: PocketIDE listens on that port during the sign-in and passes the return " +
                "to the agent in Cloud Shell. If it does (another app held the port), start the sign-in again. The Chrome " +
                "way: tap PocketIDE's tools button at the top of that page (in Chrome's own app: ⋮ > Share > PocketIDE " +
                "(Finish sign-in)).",
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
                "talks to Google, only while you use it, and nothing keeps it awake. The set-up only adds files to your home " +
                "folder. Stay within Google's rules (no mining, scanning or shared Web Preview links). A separate Google " +
                "account for development keeps your main one apart.",
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
