package com.pocketide.docs

/** Guide, part 1: what the app is, what it needs, how it works, and its agents. */
internal object GuideStart {

    val whatItIs = section(
        "what-it-is",
        "What PocketIDE is",
        "A small Linux computer inside the app, running the official coding agents full screen.",
        p(
            "PocketIDE puts a small Ubuntu computer inside one Android app. It runs the official Claude Code, " +
                "Codex and Antigravity agents, each full screen in its own room. You describe the work; the agent " +
                "writes, runs and tests the code on your phone.",
        ),
        p(
            "Your code goes to your own GitHub, one private repo per project. Your AI data (chats, memory, " +
                "settings and project secrets) is encrypted on the phone and kept in your own Google Drive. There " +
                "is no PocketIDE account, server or database.",
        ),
        p(
            "The models run on each company's servers, so the agents need the internet and your own plan with " +
                "each company. PocketIDE is not made or endorsed by Anthropic, OpenAI, Google or GitHub, and " +
                "charges nothing.",
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
            row("Accounts", "GitHub, Google, and each agent's own plan", "2-step sign-in on Google and GitHub"),
        ),
        p("Phones below the minimum are refused with a clear message."),
        info(
            "$BEING_TESTED: the memory each agent needs and the size of the set-up. As of " +
                "${DocLinks.CHECKED_ON}, before any agent, set-up downloads Ubuntu (29.9 MB), package lists " +
                "(27.9 MB), packages (37.4 MB, 146 MB installed) and code-server 4.138.0 (225 MB). The " +
                "Computer screen shows your phone's real numbers.",
        ),
    )

    val howItWorks = section(
        "how-it-works",
        "How it works",
        "Rooms, sessions and branches, and where each thing goes.",
        p(
            "The computer is Ubuntu for arm64, run through PRoot, with no root and no virtual machine. Nothing " +
                "on it is the only copy.",
        ),
        p(
            "Rooms. Each agent runs in its own room, with its own home folder. Other rooms' folders are not " +
                "placed inside it, and Claude Code's rules also deny reading them.",
        ),
        p(
            "Sessions. Every chat is a session with its own branch and working folder, such as " +
                "pocket/claude/2026-09-24-login-fix. Agents commit there; PocketIDE pushes. Put on main (the " +
                "button, or ask the agent) merges exactly that session's work after the check-post. Merged " +
                "sessions lose their branch; unmerged ones stay until you delete them.",
        ),
        p(
            "Files from the phone: the attach button takes photos; Add file to this session (agent's menu) or a " +
                "share to PocketIDE takes any file.",
        ),
        table(
            listOf("What", "Where it goes", "Who holds it"),
            row("Code and its history", "A private repo per project", "Your GitHub"),
            row(
                "Chats, memory, instructions, settings, project secrets",
                "The hidden app folder, encrypted",
                "Your Drive",
            ),
            row("Heavy builds", "GitHub Actions", "Your GitHub"),
            row("Your prompts and the code an agent reads", "The agent's model", "That agent's company"),
            row("Agent sign-ins and the computer", "Only this phone", "You"),
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
            "These three are Official. Each update is checked, tested on this phone and rolled back if it fails. " +
                "They skip the download and age checks below because their makers publish them; Antigravity's " +
                "room runs Google's own agy program, whose extension had about 14,400 downloads (as of 18 Sep 2026).",
        ),
        p(
            "New agents. A weekly Open VSX search offers an agent under More agents → New only if it has a " +
                "verified publisher, is no look-alike, has an arm64 build, 50,000 downloads, is 14 days old, and " +
                "has an agent screen. Adding it takes your tap and gives it its own room.",
        ),
        p(
            "Official vs Verified publisher. Open VSX proves who owns a publisher name, not who makes the model, " +
                "so agents found later show Verified publisher. Settings → Only official agents hides them all.",
        ),
        p(
            "Your claude.ai and ChatGPT chats do not appear here; Codex can show your Codex cloud tasks. Each " +
                "company's app memory is separate from the agent's memory (CLAUDE.md, AGENTS.md, GEMINI.md).",
        ),
    )

    val noThirdParty = section(
        "no-third-party",
        "Why only checked agents",
        "Why agents are checked, why there are no local models, and why Open VSX instead of the Marketplace.",
        p(
            "An agent reads your code and runs commands. Each extra one costs memory and sends your code to one " +
                "more company, so others are added only when they pass the checks and you tap.",
        ),
        p(
            "No local models: models small enough for a phone cannot do agent work across many files.",
        ),
        p(
            "Open VSX, not the Marketplace. Extensions come only from Open VSX, run by the Eclipse Foundation: " +
                "Microsoft's VS Code Marketplace may be used only by Microsoft's own products. Most AI companies " +
                "publish on both.",
        ),
        link("Open VSX", DocLinks.OPEN_VSX),
    )

    val all = listOf(whatItIs, requirements, howItWorks, agents, noThirdParty)
}
