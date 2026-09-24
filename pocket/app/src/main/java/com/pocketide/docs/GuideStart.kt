package com.pocketide.docs

/** Guide, part 1: what the app is, what it needs, how it works, and its agents. */
internal object GuideStart {

    val whatItIs = section(
        "what-it-is",
        "What PocketIDE is",
        "A small Linux computer inside the app, running the official coding agents full screen.",
        p(
            "PocketIDE puts a small Ubuntu computer inside one Android app. It runs the official " +
                "Claude Code, Codex and Antigravity agents, each full screen in its own room. You describe " +
                "the work; the agent writes, runs and tests the code on your phone.",
        ),
        p(
            "Your code goes to your own GitHub, one private repo per project. Your AI data (chats, memory, " +
                "settings and project secrets) is encrypted on the phone and kept in your own Google Drive. " +
                "Heavy builds run on your GitHub Actions. There is no PocketIDE account, server or database.",
        ),
        p(
            "The AI models run on each company's servers, so the agents need the internet. You use your own " +
                "plan with each company. PocketIDE charges nothing.",
        ),
    )

    val requirements = section(
        "requirements",
        "Minimum requirements",
        "What a phone needs to run PocketIDE, and what makes it comfortable.",
        table(
            listOf("", "Minimum", "Recommended"),
            row("Android", "10 or newer, 64-bit (arm64), Google Play services", "12 or newer"),
            row("Memory (RAM)", "4 GB: Claude or Codex, plus Antigravity", "6 GB or more: all three together"),
            row("Free storage", "8 GB (the computer takes about 2.5 GB)", "16 GB"),
            row("Screen lock", "PIN, pattern or password", "Fingerprint too"),
            row("Internet", "Wi-Fi for set-up (about 1.5 GB)", "Wi-Fi or good 4G/5G"),
            row(
                "Accounts",
                "GitHub, Google, and each agent's own plan",
                "2-step sign-in on Google and GitHub",
            ),
        ),
        p(
            "The app refuses, with a clear message, phones that are 32-bit only, have no Play services, run " +
                "Android 9 or older, or have less than 4 GB of RAM. A 3 GB phone cannot hold the computer next " +
                "to Android, so it is refused rather than left to crash.",
        ),
    )

    val howItWorks = section(
        "how-it-works",
        "How it works",
        "Rooms, sessions and branches, and where each thing goes.",
        p(
            "The computer is Ubuntu for arm64, run through PRoot, with no root and no virtual machine. Reset " +
                "computer rebuilds it from the same recipe; nothing on it is the only copy.",
        ),
        p(
            "Rooms. Each agent runs in its own room, with its own home folder. Other rooms' folders are not " +
                "placed inside it, so for it they do not exist. Claude Code also gets rules that deny reading them.",
        ),
        warn(
            "A room stops accidental reading, which is the real risk. It is not a wall against deliberately " +
                "harmful code, because PRoot is not a sandbox. That is why only checked agents run here.",
        ),
        p(
            "Sessions. Every chat is a session with its own branch and working folder, such as " +
                "pocket/claude/2026-09-24-login-fix. Put on main (the button, or ask the agent) merges exactly " +
                "that session's work after the check-post; the agent resolves any conflict. Merged sessions " +
                "lose their branch; unmerged ones stay until you delete them.",
        ),
        table(
            listOf("What", "Where it goes"),
            row("Code and its history", "Your GitHub, a private repo per project"),
            row(
                "Chats, memory, instructions, settings, project secrets",
                "Encrypted, in your Drive's hidden app folder",
            ),
            row("Heavy builds", "Your GitHub Actions"),
            row("Agent sign-ins", "Only this phone"),
        ),
    )

    val agents = section(
        "agents",
        "The agents",
        "The official three, how new agents are found and added, and your claude.ai and ChatGPT chats.",
        table(
            listOf("Agent", "Publisher", "You sign in with"),
            row("Claude Code", "Anthropic", "A Claude plan (Pro, Max, Team, Enterprise) or a Console account"),
            row("Codex", "OpenAI", "A ChatGPT plan that includes Codex"),
            row("Antigravity", "Google", "A Google account"),
        ),
        p(
            "These three are Official. They update themselves; each update is checked, tested on this phone, " +
                "and rolled back if it fails. Each agent's help page is written from its own details.",
        ),
        p(
            "New agents. Once a week the app searches Open VSX for AI and chat extensions. It offers one under " +
                "More agents → New only if it has a verified publisher, is not a look-alike, has an arm64 or " +
                "universal build, at least 50,000 downloads, is at least 14 days old, and has an agent screen.",
        ),
        p(
            "Adding one takes your tap, after a card with the publisher, the downloads and \"Your prompts and " +
                "code will go to this publisher's service\". It is tested on this phone and gets its own room. " +
                "Removing it deletes its room.",
        ),
        p(
            "Official vs Verified publisher. Open VSX proves who owns a publisher name, not who makes the " +
                "model. So a new official agent and a community one look the same to any check, and both show " +
                "Verified publisher. Community agents add a note: that company is not the model maker, and your " +
                "code also goes to the model service it uses. Settings → Only official agents hides them all.",
        ),
        p(
            "Your claude.ai and ChatGPT chats do not appear here. Each agent keeps its own sessions; Codex can " +
                "show your Codex cloud tasks. Each company's app memory is separate from the agent's project " +
                "memory (CLAUDE.md, AGENTS.md, GEMINI.md).",
        ),
    )

    val noThirdParty = section(
        "no-third-party",
        "Why only these agents",
        "Why agents are checked, why there are no local models, and why Open VSX instead of the Marketplace.",
        p(
            "An agent reads your code and runs commands. Every extra agent costs memory and storage and sends " +
                "your code to one more company. So PocketIDE ships the model makers' own agents, and adds " +
                "others only when they pass the checks and you tap.",
        ),
        p(
            "No local models. Models small enough for a phone cannot do agent work across many files; good " +
                "coding models need a large graphics card on a server.",
        ),
        p(
            "Open VSX, not the Marketplace. Extensions come only from Open VSX, run by the Eclipse Foundation. " +
                "Microsoft's VS Code Marketplace may be used only by Microsoft's own products. An extension " +
                "published only there does not appear here until its publisher also publishes on Open VSX. " +
                "Most AI companies do, because editors such as Cursor, VSCodium and Windsurf use it.",
        ),
        link("Open VSX", DocLinks.OPEN_VSX),
    )

    val all = listOf(whatItIs, requirements, howItWorks, agents, noThirdParty)
}
