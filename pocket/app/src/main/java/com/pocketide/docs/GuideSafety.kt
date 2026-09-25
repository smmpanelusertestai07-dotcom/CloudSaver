package com.pocketide.docs

/** Guide, part 3: security, privacy and safety. */
internal object GuideSafety {

    val security = section(
        "security",
        "Security",
        "The locks on your accounts, code and chats, and what a harmful agent could reach.",
        p(
            "PocketIDE is a GitHub App signed in with a device code. It works only on the repos you choose, " +
                "never deletes a repo, and keeps its token in the Android Keystore. In Drive it can use only its " +
                "own hidden folder.",
        ),
        p(
            "The check-post runs before every push. If it finds secrets, AI data or very large files, nothing " +
                "is pushed and you see what it found. APK, AAB and other build outputs are held too; builds live " +
                "in Media.",
        ),
        p(
            "Changes to .github/workflows or .github/actions wait for you to read the diff and tap Approve: " +
                "workflow code runs with your Secrets.",
        ),
        p(
            "The app never takes a token, key or setting from the computer; only Variables enter a room. " +
                "Settings that can run code, such as hooks, are rewritten at each room start, and an agent's " +
                "change to them is kept only after you approve it.",
        ),
        p(
            "The agents' screens listen only on this phone, with a new secret each launch. App lock uses your " +
                "fingerprint or PIN, and FLAG_SECURE hides PocketIDE in Recents and screenshots.",
        ),
        warn(
            "Prompt injection: a file, issue or web page an agent reads can try to give it orders. Room rules " +
                "treat such text as data, but review changes before Put on main and approve only commands you " +
                "understand.",
        ),
        table(
            listOf("A tricked or harmful agent can reach", "Answer"),
            row("Its room, its sessions' folders, its sign-in and ports", "Yes"),
            row("Other rooms", "No: they are not placed in its room"),
            row("Your GitHub token, Drive access, the key, Secrets", "No: they never enter the computer"),
            row("Your phone's files, photos and apps", "No: Android keeps the app apart"),
        ),
        p(
            "PRoot is not a sandbox: rooms stop accidental reading, not a determined attack. Codex runs " +
                "without its own sandbox here: its Linux sandbox needs features PRoot does not give, so the room " +
                "is the only boundary. Scheduled Codex tasks run the same way. That is why only checked agents run.",
        ),
        p(
            "The agents' test browser, installed only when an agent asks, runs Chromium without its own " +
                "sandbox, because PRoot looks like root to it. A harmful page can reach what that agent's room " +
                "can reach, nothing more. It stays off for someone else's projects.",
        ),
        table(
            listOf("Risk", "Safeguard"),
            row("Showing media", "Only PNG, JPEG, WebP, GIF, MP4 and WebM; other files show as plain files."),
            row("HTML or PDF from agents", "Opened with JavaScript off and no file or network access."),
            row("Built APKs", "Installed only on your tap, after showing the package and signer."),
            row("Saving or sharing", "Only through Android's share sheet, when you tap."),
            row("Viruses", "No phone antivirus scans Linux files reliably, so files are contained instead."),
        ),
    )

    val privacy = section(
        "privacy",
        "Privacy",
        "Who sees what, what each AI company keeps, and the switches that stop training on your chats.",
        table(
            listOf("Who", "What they can see"),
            row(
                "The company whose agent you use",
                "Your prompts, its replies, and the code and images the agent reads",
            ),
            row("An extra agent's publisher", "The same, and so does its model service"),
            row("GitHub", "Your code and build logs, and Half G"),
            row("Google", "Encrypted files and their sizes, and Half D"),
            row("PocketIDE's developer", "Nothing: no server, no analytics"),
        ),
        p(
            "Each company keeps what you send under its own policy; deleting a chat in PocketIDE does not " +
                "delete its copy.",
        ),
        bullets(
            "Anthropic: on Free, Pro and Max, chats are kept 30 days with \"Help improve Claude\" off, and up " +
                "to 5 years with it on.",
            "OpenAI: ChatGPT and Codex content may be used for training unless you turn off \"Improve the " +
                "model for everyone\". Codex has a separate switch for full environments.",
            "Google: Antigravity may use your interactions to improve its models, and people may review them, " +
                "unless you turn off Settings → Account → Enable Telemetry.",
        ),
        info("Policies as of ${DocLinks.CHECKED_ON}. $LABELS_NOTE"),
        link("Claude privacy settings", DocLinks.CLAUDE_PRIVACY),
        link("Claude Code data usage", DocLinks.CLAUDE_DATA_USAGE),
        link("ChatGPT data controls", DocLinks.CHATGPT_DATA_CONTROLS),
        link("OpenAI data controls FAQ", DocLinks.OPENAI_DATA_FAQ),
        link("Antigravity settings", DocLinks.ANTIGRAVITY_SETTINGS),
        link("Antigravity terms", DocLinks.ANTIGRAVITY_TERMS),
        warn(
            "Never paste passwords or keys into a chat: they go to the AI company. Put them in Project → " +
                "Secrets, which only set-up steps and your Actions builds get.",
        ),
    )

    val safety = section(
        "safety",
        "Safety",
        "How agents are kept from harming your project or your phone.",
        p(
            "Agents work on their own, but only on a session's branch. Main changes only when you tap Put on " +
                "main or ask, so read the session's Changes first.",
        ),
        bullets(
            "Small work runs on the phone; heavy work goes to GitHub Actions, and the agent checks the result.",
            "An agent never declines work that can be done somewhere; it names the place in one line.",
            "It asks you only for real decisions: money, accounts, deleting.",
        ),
        p(
            "The limiter watches memory, heat, battery, storage and Android's process cap. It queues or pauses " +
                "work, closes idle agents first, never stops one mid-write, and tells you why.",
        ),
        p(
            "Scheduled tasks run a saved prompt only while the phone is charging on Wi-Fi. The result becomes " +
                "a session for you to review ($BEING_TESTED).",
        ),
    )

    val all = listOf(security, privacy, safety)
}
