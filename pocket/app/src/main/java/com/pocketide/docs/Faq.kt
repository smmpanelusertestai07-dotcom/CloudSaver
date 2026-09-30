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
            "For a while. Google ends a Cloud Shell session 40 minutes after you stop using it, and PocketIDE never keeps it " +
                "awake, because that breaks Google's rules. For long tasks, look in now and then, or give the agent smaller steps.",
        ),
        faq(
            "phone",
            "start",
            "Does it heat the phone or drain the battery?",
            "No more than a web page: the agents and VS Code run in Google's cloud, and the phone only shows them. The old " +
                "phone computer of PocketIDE 5 is gone.",
        ),
        faq(
            "chrome",
            DocsContent.IDE_ID,
            "Why do the agents open in Chrome, not inside the app?",
            "Cloud Shell needs your Google sign-in, and Google allows it only in a real browser. The Chrome tab is dressed as " +
                "PocketIDE's: its arrow returns here, its tools button shows every agent, and Chrome's menu (⋮) switches between " +
                "them. Chrome keeps its own ⋮ in every tab; no app can remove it.",
        ),
        faq(
            "sign-in-localhost",
            "agents",
            "A sign-in ends at \"localhost refused to connect\".",
            "The agent waits for that page in Cloud Shell, but on a phone it opens on the phone. PocketIDE usually catches it " +
                "by itself; when it does not, tap PocketIDE's tools button at the top of that page (in Chrome's own app: ⋮ > " +
                "Share > PocketIDE (Finish sign-in)), and PocketIDE hands it to the agent in Cloud Shell.",
        ),
        faq(
            "antigravity-screen",
            "agents",
            "Why does Antigravity open without VS Code?",
            "Google's Antigravity program (agy) has its own screen, the same one its VS Code panel shows, but serves it only to " +
                "localhost. PocketIDE's bridge in your Cloud Shell passes it on to Web Preview, so Antigravity opens full screen, " +
                "signed in with Google, no command line. Its projects are in ~/projects/antigravity.",
        ),
        faq(
            "more-extensions",
            "agents",
            "Can I add other extensions?",
            "Yes: in Claude Code's or Codex's VS Code, Tools > Extensions finds everything on Open VSX, and Tools > Install " +
                "from a link installs one that is not there, from its maker's .vsix link. Each VS Code keeps its own; Open VSX's " +
                "update by themselves. Microsoft's own extensions (Pylance, C# Dev Kit, Remote, Live Share) are not on Open VSX.",
        ),
        faq(
            "to-github",
            "projects",
            "How do I put a project on GitHub?",
            "Open the Terminal in the agent's VS Code, run gh auth login once (GitHub's page opens in Chrome), then git push. " +
                "Or ask the agent to do it: it uses the same terminal.",
        ),
        faq(
            "account-safe",
            DocsContent.COMPUTER_ID,
            "Can this get my Google account in trouble?",
            "PocketIDE uses Cloud Shell as Google intends: you open it yourself, nothing keeps it awake, and the set-up only " +
                "adds files to your home folder. Stay within Google's rules (no mining, scanning or shared Web Preview links). " +
                "A separate Google account for development keeps your main one apart.",
        ),
        faq(
            "set-up-again",
            DocsContent.TROUBLE_ID,
            "PocketIDE asks for the set-up again. Why?",
            "It has not opened Cloud Shell for ${CloudShell.ASK_AGAIN_AFTER_DAYS} days, and Google deletes an unused home folder " +
                "after ${CloudShell.DELETED_AFTER_DAYS}. Paste the command again: it only adds what is missing.",
        ),
        faq(
            "old-version",
            DocsContent.YOUR_DATA_ID,
            "I used an earlier PocketIDE. What happens to it?",
            "Version 6 works in Google Cloud Shell. PocketIDE 5 kept a computer on the phone: it stays until you delete it in " +
                "Settings > Your data, where you can first save its projects and chats as a zip. Codespaces you made with " +
                "version 4 stay in your GitHub account until you delete them there.",
        ),
    )
}
