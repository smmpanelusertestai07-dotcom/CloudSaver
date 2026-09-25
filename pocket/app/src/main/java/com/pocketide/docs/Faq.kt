package com.pocketide.docs

import com.pocketide.rooms.BrowserTools

/** The questions people actually ask, each answered in a few short sentences and linked to its section. */
internal object Faq {

    val all = listOf(
        faq(
            "visible-to-anyone", "privacy",
            "Is my code or chat visible to anyone?",
            "Your code is in private GitHub repos that only you can open, unless you make one public. Your " +
                "chats are encrypted before they reach Drive, so Google cannot read them. The AI company of " +
                "the agent you use sees what that agent sends it.",
        ),
        faq(
            "what-companies-get", "privacy",
            "What does each AI company get?",
            "Your prompts, the agent's replies, and the code and images the agent reads. Each company keeps " +
                "them under its own policy. Privacy lists each company's switch that stops training on your chats.",
        ),
        faq(
            "agents-read-each-other", "how-it-works",
            "Can one agent read another's chats?",
            "Not by accident. Each agent runs in its own room, and other rooms' folders are not placed inside " +
                "it. A room is not a wall against deliberately harmful code, which is why only checked agents run.",
        ),
        faq(
            "lose-phone", "recovery",
            "What if I lose my phone?",
            "Your synced work is safe. On a new phone, sign in to GitHub and Google; the two key halves " +
                "rebuild the key and everything synced comes back. To lock out the old phone, remove " +
                "PocketIDE's access from GitHub and Google in a browser.",
        ),
        faq(
            "uninstall", "deleting",
            "What if I uninstall?",
            "Android erases the phone copy and its Keystore key. Your code on GitHub and your encrypted data " +
                "in Drive stay. Reinstall and sign in, and everything comes back. Wait until sync is finished " +
                "first, or unsynced work is lost.",
        ),
        faq(
            "new-phone", "recovery",
            "How do I move to a new phone?",
            "Install PocketIDE on the new phone and sign in to GitHub and Google. A restore plan shows what " +
                "downloads now and what waits. Then sign in to each agent again.",
        ),
        faq(
            "another-google-account", "recovery",
            "How do I move to another Google account?",
            "Your data → Move to another Google account. The app copies every file, one at a time, makes a new " +
                "key half there, checks everything, and then asks before erasing the old copy.",
        ),
        faq(
            "delete-chat-or-all", "deleting",
            "How do I delete one chat, or everything?",
            "One chat: delete it in Chats or in the agent's own screen; it goes to Recently deleted. " +
                "Everything: Your data → Delete everything. You can also delete it all from a browser, " +
                "without the app.",
        ),
        faq(
            "where-recently-deleted", "deleting",
            "Where is Recently deleted?",
            "Chats → Recently deleted. Restore a chat there within 30 days, or use Delete forever. Drive's own " +
                "Trash is not used.",
        ),
        faq(
            "remember-code", "the-key",
            "Do I need to remember any code?",
            "No. The key is split between your Drive and your GitHub, so signing in to both is enough. Only the " +
                "optional extra password must be remembered.",
        ),
        faq(
            "extra-password", "the-key",
            "What does the extra password change?",
            "It wraps the GitHub half of the key, so even someone holding both your accounts cannot read your " +
                "chats. It is asked only when you set up a new phone. If you forget it, the chats are lost.",
        ),
        faq(
            "why-only-these-agents", "no-third-party",
            "Why only these agents?",
            "They are the model makers' own agents, and each extra agent sends your code to one more company. " +
                "Others are added only when they pass the checks and you tap.",
        ),
        faq(
            "new-official-agent", "agents",
            "When will a new official agent appear?",
            "When its maker publishes it on Open VSX and it passes the checks, the weekly search finds it and " +
                "offers it under More agents → New, with a notification. It shows Verified publisher, and you " +
                "add it with one tap.",
        ),
        faq(
            "why-no-local-models", "no-third-party",
            "Why no local models?",
            "Models small enough for a phone cannot do agent work across many files. Good coding models need a " +
                "large graphics card on a server.",
        ),
        faq(
            "claude-chatgpt-chats", "agents",
            "Can I see my claude.ai or ChatGPT chats here?",
            "No. The agents keep their own sessions. The Codex extension can show your Codex cloud tasks, but " +
                "not ordinary ChatGPT chats.",
        ),
        faq(
            "offline", "limits",
            "Does it work offline?",
            "The computer, your files and Preview work offline. The agents need the internet because their " +
                "models run on the companies' servers. Sync waits and then catches up.",
        ),
        faq(
            "why-locked", "conditions",
            "Why is the app locked?",
            "Either the app lock is on, or access to Google or GitHub was removed, or Drive has been full for " +
                "too long, or another phone took over. The lock screen says which, and how to fix it. Your data " +
                "is held safely meanwhile.",
        ),
        faq(
            "drive-full", "your-data",
            "What happens when Drive is full?",
            "New chats wait safely on the phone and a banner explains. Free space in Google storage or delete " +
                "old chats. If sync still fails a day later, the app locks until there is space, so nothing is lost.",
        ),
        faq(
            "free-builds", "github-actions",
            "How many builds do I get free?",
            "It depends on your GitHub plan and build times. The Usage screen estimates the builds left this " +
                "month from your own builds. GitHub Actions has the plan table.",
        ),
        faq(
            "public-or-private", "github-actions",
            "Public or private repo?",
            "Private for your own apps. Public for open-source projects: Actions is free there, but anyone can " +
                "see the code.",
        ),
        faq(
            "ios-apps", "limits",
            "Can it build iOS apps?",
            "Yes, on GitHub Actions' macOS runner; the phone cannot. Installing on an iPhone also needs an " +
                "Apple developer account. macOS minutes use up your allowance fastest.",
        ),
        faq(
            "why-no-docker", "limits",
            "Why no Docker on the phone?",
            "Docker needs container features of a full Linux kernel, which Android does not give apps. The " +
                "agent sends Docker work to a GitHub Actions workflow instead.",
        ),
        faq(
            "battery-saver", "conditions",
            "Does battery saver stop it?",
            "No. While agents work, a visible notice keeps the computer running with its network. Some phone " +
                "makers add their own killers; follow the one-time step in Conditions.",
        ),
        faq(
            "space-used", "your-data",
            "How much space does it use?",
            "The computer takes about 2.5 GB, plus your projects and caches. Your data shows the real sizes on " +
                "the phone, in Drive and on GitHub.",
        ),
        faq(
            "share-drive-or-keyring", "the-key",
            "Is it safe to share my Drive or keyring?",
            "The Drive folder cannot be shared. Never share pocketide-keyring: it holds half the lock. If it " +
                "becomes public or gains a collaborator, the app makes a new key and tells you.",
        ),
        faq(
            "deleted-really-gone", "deleting",
            "When is a deleted chat really gone?",
            "Thirty days after you delete it, or at once with Delete forever. Then the file is erased from Drive " +
                "for good. The AI company's copy is deleted only on its side.",
        ),
        faq(
            "see-edit-drive-data", "without-the-app",
            "Where can I see or edit my Drive data?",
            "In PocketIDE: Your data and Chats. Drive's website shows only the total size and can delete it " +
                "all. The files themselves are hidden and encrypted, on purpose.",
        ),
        faq(
            "why-only-actions", "github-actions",
            "Why only GitHub Actions?",
            "It already holds your code, so no new account or company sees it. The app ships ready templates, " +
                "and results come back into Media.",
        ),
        faq(
            "mobile-data", "your-data",
            "How much mobile data does it use?",
            "As little as possible: only new chat parts are sent, compressed, and big downloads wait for " +
                "Wi-Fi. Settings → Mobile data sets a daily limit, which counts only mobile data, and shows " +
                "what was used.",
        ),
        faq(
            "terminal", "how-it-works",
            "Where is the terminal, and do I need it?",
            "It is the >_ tab, last in each project, with the keyboard bar. You do not need it for sign-in or " +
                "normal work.",
        ),
        faq(
            "see-website", "limits",
            "Can I see my website while an agent builds it?",
            "Yes. When the agent runs a dev server, the Preview tab shows the site in the phone's own browser " +
                "view, and you can tap around it.",
        ),
        faq(
            "lose-github-or-phone", "recovery",
            "If I lose GitHub (or my phone), are my chats safe?",
            "Yes, if you lose one of them. Losing both before you reconnect GitHub makes the chats unreadable; " +
                "a saved key copy covers that case. Code on GitHub is never affected.",
        ),
        faq(
            "count-after-reinstall", "deleting",
            "Does the 30-day count restart after a reinstall?",
            "No. The deleted-on date is kept in Drive, so the count continues from the original date.",
        ),
        faq(
            "companies-keep-chats", "privacy",
            "Do the AI companies keep my chats anyway?",
            "They keep what the agent sends them, under their own policies. Deleting a chat in PocketIDE does " +
                "not delete their copy; use each company's data page.",
        ),
        faq(
            "api-keys", "privacy",
            "Where do I put API keys so the agent can use them safely?",
            "In Project → Secrets. Keys the agent must use itself go in Variables; keys only builds need go in " +
                "Secrets, which the agent never sees. Never paste them into a chat.",
        ),
        faq(
            "marketplace-extension", "no-third-party",
            "Will an extension on the VS Code Marketplace show up here?",
            "Only if its publisher also puts it on Open VSX. Microsoft's Marketplace may be used only by " +
                "Microsoft's own products.",
        ),
        faq(
            "scheduled", "safety",
            "Can agents run tasks on a schedule?",
            "Yes. A scheduled task runs a saved prompt while the phone is charging on Wi-Fi. The result waits " +
                "as a session for you to review.",
        ),
        faq(
            "reopen-media", "your-data",
            "When I reopen a chat, will I see the screenshots and videos again?",
            "Yes: the chat can always be read in Chats, on any phone, and the agent's screenshots and videos " +
                "come back in the session's Media. The agent's own screen shows it too (Codex and Antigravity on a " +
                "new phone: $BEING_TESTED). A video still waiting for Wi-Fi shows a chip.",
        ),
        faq(
            "virus", "security",
            "Can an agent download a virus onto my phone?",
            "It can download a harmful file inside the computer, but Android's app sandbox keeps it away from " +
                "other apps, your photos and your files. PocketIDE shows only safe media formats and installs " +
                "an APK only on your tap.",
        ),
        faq(
            "test-browser", "security",
            "Can the agents test my app in a browser?",
            "Yes. An agent calls install_browser once, which downloads a test browser of about " +
                "${BrowserTools.DOWNLOAD_BYTES / 1_000_000} MB for every room; big downloads wait for Wi-Fi unless " +
                "you allow mobile data. That browser runs without Chromium's own sandbox, so a harmful page can " +
                "reach that agent's room, nothing more. It stays off for someone else's projects.",
        ),
        faq(
            "minutes-left", "github-actions",
            "How many free build minutes do I have left?",
            "The Usage screen shows your real plan, minutes used this month by system, and the reset date, " +
                "read live from GitHub.",
        ),
        faq(
            "other-companies-agents", "agents",
            "Why are there agents from other companies in \"More agents\"?",
            "The weekly search offers any verified publisher's agent that passes every check. They show " +
                "Verified publisher, not Official, and nothing is added without your tap. Settings → Only " +
                "official agents hides them.",
        ),
        faq(
            "faster-phone", "requirements",
            "Would a faster phone make the agents faster?",
            "Not their thinking: the models run on the companies' servers. A faster phone with more memory " +
                "speeds up installs, builds and tests, and runs more agents at once.",
        ),
        faq(
            "account-suspended", "terms",
            "Can this get my account suspended?",
            "PocketIDE runs each company's own agent, signed in with your own account, as on any Linux " +
                "computer. Follow each company's terms: share no account, and use Actions minutes only to build " +
                "and test your projects.",
        ),
        faq(
            "files-in-repo", "how-it-works",
            "Does PocketIDE add files to my repo?",
            "No chats, keys or agent settings: those live in each room's home, never in your repo. Only a " +
                "build template you add is committed, to the session's branch, like any other change.",
        ),
        faq(
            "team", "what-it-is",
            "Does it work for a team?",
            "It is made for one person, one phone at a time. Teammates share a project's repo on GitHub as " +
                "usual, while each person's chats, key and Secrets stay in their own PocketIDE.",
        ),
        faq(
            "vs-code-pylance", "no-third-party",
            "Is this VS Code, and can I add Pylance?",
            "No. code-server, the open-source engine behind the Claude and Codex screens, stays hidden, and " +
                "only agents are added. Pylance and other Microsoft extensions may be used only in Microsoft's " +
                "own products.",
        ),
        faq(
            "network-drops", "conditions",
            "What if the network drops mid-answer?",
            "The answer in progress may stop with an error. Your session, files and commits stay. When the " +
                "network is back, ask the agent to continue; sync resumes by itself.",
        ),
        faq(
            "file-from-phone", "how-it-works",
            "How do I get a file from my phone into a project?",
            "Photos and videos: the agent's attach button opens Android's photo picker. Any other file: in the " +
                "agent's menu, tap Add file to this session, or share it to PocketIDE from another app and pick " +
                "the session. Then choose: into the project, for the agent to use and commit, or to Media.",
        ),
    )
}
