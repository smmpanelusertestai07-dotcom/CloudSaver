package com.pocketide.docs

/** Guide, part 3: security, privacy and safety. */
internal object GuideSafety {

    val security = section(
        "security",
        "Security",
        "The locks that protect your accounts, your code and your chats, and what a harmful agent could reach.",
        p(
            "GitHub. PocketIDE is a GitHub App signed in with a device code, so it holds no client secret. It " +
                "works only on the repos you choose, can create repos but never deletes one, and reads your " +
                "plan to show real Actions usage. Its token stays in the Android Keystore, never in the computer.",
        ),
        p("Google. PocketIDE can use only its own hidden app folder in Drive, not your other files."),
        p(
            "The check-post runs before every push. If it finds secrets, AI data or very large files, nothing " +
                "is pushed and you see what it found.",
        ),
        p(
            "The app never takes a token, key or setting from inside the computer, and only Variables enter a " +
                "room. Settings that can run code, such as hooks and MCP servers, are rewritten by PocketIDE at " +
                "each room start; a change an agent makes is kept only after you approve it.",
        ),
        p(
            "The agents' screens listen only on this phone, with a new secret each launch. Other links open in " +
                "Chrome. App lock uses your fingerprint or PIN, and PocketIDE's screen is hidden in Recents and " +
                "screenshots (FLAG_SECURE).",
        ),
        warn(
            "Text an agent reads (a file, an issue, a web page, a download) can try to give it orders. This is " +
                "prompt injection. Each room's rules say such text is data, never instructions, but no rule is " +
                "perfect: review the changes before Put on main, and approve only commands you understand.",
        ),
        table(
            listOf("If an agent is tricked or harmful, can it reach", "Answer"),
            row("Its room's home and its sessions' folders", "Yes"),
            row("Its own sign-in, and the ports it opens", "Yes"),
            row("Other rooms", "No: they are not placed in its room"),
            row("Your GitHub token, Drive access, the key and your Secrets", "No: they never enter the computer"),
            row("Your phone's files, photos and other apps", "No: Android keeps the app apart from them"),
        ),
        p(
            "PRoot is not a sandbox, so rooms stop accidental reading, not a determined attack. Codex's own " +
                "sandbox needs Linux features PRoot may lack, so inside its room it can run without one " +
                "($BEING_TESTED). That is why only checked agents run here.",
        ),
        table(
            listOf("Risk", "Safeguard"),
            row(
                "Harmful files an agent downloads",
                "They stay in the app's private computer, away from other apps, your photos and your files.",
            ),
            row(
                "Showing media",
                "Only PNG, JPEG, WebP, GIF, MP4 and WebM are shown; other files appear as plain files.",
            ),
            row("HTML or PDF from agents", "Opened with JavaScript off and no file or network access."),
            row("Built APKs", "Installed only on your tap, after showing the package name and signer."),
            row("Saving or sharing", "Only through Android's share sheet, when you tap."),
        ),
        info(
            "There is no reliable antivirus for Linux files on a phone, so PocketIDE contains files instead of " +
                "pretending to scan.",
        ),
    )

    val privacy = section(
        "privacy",
        "Privacy",
        "Who sees what, what each AI company keeps, and the switches that stop training on your chats.",
        table(
            listOf("Who", "What they can see"),
            row("You", "Everything, through the app"),
            row(
                "The company whose agent you use",
                "Your prompts, its replies, and the code and images the agent reads",
            ),
            row("An extra agent's publisher", "The same, plus the model service it uses"),
            row("GitHub", "Your code and build logs, and Half G"),
            row("Google", "Encrypted files and their sizes, and Half D"),
            row("PocketIDE's developer", "Nothing: no server, no analytics"),
        ),
        p(
            "Each company keeps what you send under its own policy. Deleting a chat in PocketIDE does not " +
                "delete the company's copy. The history you reopen comes from your phone and Drive, not from them.",
        ),
        bullets(
            "Anthropic: on Free, Pro and Max, chats are kept 30 days with \"Help improve Claude\" off, and up " +
                "to 5 years with it on.",
            "OpenAI: ChatGPT and Codex content may be used for training unless you turn off \"Improve the " +
                "model for everyone\". Codex has a separate switch for full environments.",
            "Google: Antigravity may use your interactions to improve its models, and people may review them. " +
                "Turn off Settings → Account → Enable Telemetry.",
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
                "Secrets. Secrets never reach an agent; only set-up steps and your Actions builds get them.",
        ),
    )

    val safety = section(
        "safety",
        "Safety",
        "How agents are kept from harming your project or your phone.",
        p(
            "Agents work on their own, but only inside a session's branch. Main changes only when you tap Put " +
                "on main or ask for it, so read the session's Changes first.",
        ),
        bullets(
            "Small, quick work runs on the phone; heavy work goes to GitHub Actions, and the agent checks the " +
                "result and retries.",
            "An agent never declines work that can be done somewhere. It picks the place and says so in one line.",
            "It asks you only for real decisions: money, accounts, deleting.",
            "It never reads outside its room, never pushes secrets, and touches main only when asked.",
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
