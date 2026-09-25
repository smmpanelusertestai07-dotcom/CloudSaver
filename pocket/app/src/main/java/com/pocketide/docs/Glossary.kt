package com.pocketide.docs

/** One meaning per word, in the words the app uses. */
internal object Glossary {

    val all = listOf(
        term(
            "Agent",
            "An AI program that reads your code, runs commands and makes changes. The model behind it runs on its " +
                "company's servers.",
        ),
        term("Official", "The label of the three built-in agents: Claude Code, Codex and Antigravity."),
        term(
            "Verified publisher",
            "Open VSX has confirmed who owns the publisher name. It says nothing about who makes the model or " +
                "what the code does.",
        ),
        term(
            "Open VSX",
            "The open extension registry run by the Eclipse Foundation. PocketIDE takes extensions only from here.",
        ),
        term(
            "VS Code Marketplace",
            "Microsoft's extension store. Its terms allow only Microsoft's own products to use it.",
        ),
        term("Extension", "An add-on for a VS Code-style editor. Claude Code and Codex come as extensions."),
        term(
            "Extension id",
            "The publisher.name string that names one extension exactly, such as anthropic.claude-code. " +
                "Look-alikes differ by a letter or two.",
        ),
        term(
            "code-server",
            "The open-source engine that shows an agent's extension full screen. You never see it as an editor.",
        ),
        term("Computer", "The Ubuntu Linux system inside PocketIDE, where agents run programs and tests."),
        term(
            "PRoot",
            "The tool that runs Ubuntu on the phone's own kernel without root or a virtual machine. It is not a " +
                "sandbox.",
        ),
        term(
            "Room",
            "One agent's own part of the computer, with its own home folder. Other rooms' folders are not visible " +
                "from it.",
        ),
        term("Session", "One chat with an agent, with its own branch and working folder."),
        term("Branch", "A separate line of commits in a repo. Each session works on its own branch."),
        term(
            "Worktree",
            "A working folder for one branch, so several sessions can work at once without touching each other.",
        ),
        term("main", "The branch that holds your project's accepted code."),
        term("Put on main", "Merges exactly one session's work into main, after the check-post."),
        term("Check-post", "The check before every push that blocks secrets, AI data and very large files."),
        term("Commit", "A saved step in a project's history. You can always go back to one."),
        term(
            "Repository (repo)",
            "A project's code and full history on GitHub. PocketIDE makes a private one per project.",
        ),
        term("pocketide-keyring", "Your private GitHub repo that holds Half G of the key. Never share it."),
        term("Key", "The random key that encrypts every file PocketIDE puts in Drive."),
        term(
            "Half D and Half G",
            "The two halves of the key, one in Drive and one in GitHub. Either one alone is useless.",
        ),
        term("Keystore", "Android's hardware-protected store. It holds the full key and your GitHub token."),
        term(
            "Extra password",
            "An optional password that wraps Half G. It is asked only on a new phone; forget it and the chats are " +
                "lost.",
        ),
        term("Key copy", "The key as text, saved by you. It covers losing the phone and GitHub together."),
        term("Vault", "Your encrypted AI data: chats, memory, instructions, settings, Variables and Secrets."),
        term(
            "Hidden app folder",
            "A Drive folder only PocketIDE can use. You see its size on Drive's website, not its files.",
        ),
        term("Recently deleted", "Where deleted chats wait for 30 days before they are erased from Drive."),
        term(
            "Hook",
            "A command an agent runs by itself on an event. It can run code, so PocketIDE shows you any new one.",
        ),
        term(
            "Your data",
            "The screen that lists everything PocketIDE stores, by type and size, and lets you delete it.",
        ),
        term("Restore plan", "What a new phone will download now and what waits, shown before anything downloads."),
        term("Conflict copy", "A session kept separately when two phones changed the same thing, so nothing is lost."),
        term("Variables", "Settings the agent may see, such as a test URL. They are set in its room."),
        term("Secrets", "Keys and tokens the agent never sees. Only set-up steps and GitHub Actions builds get them."),
        term(
            "Preview",
            "The project tab that shows a dev server running on the phone, in the phone's own browser view.",
        ),
        term("Media", "The project tab with screenshots, videos and APKs from agents and from builds."),
        term("Terminal", "The >_ tab in each project: a shell in the session's room, with the keyboard bar."),
        term("GitHub Actions", "GitHub's build computers. They run a workflow and then shut down."),
        term(
            "Workflow",
            "A file in a repo that tells GitHub Actions what to build or test. PocketIDE's run only when started.",
        ),
        term("Runner", "The machine that runs a workflow: Linux, Windows or macOS."),
        term("Included minutes", "The build minutes your GitHub plan gives each month for private repos."),
        term("Minute multiplier", "How much more a Windows or macOS minute costs than a Linux one."),
        term("Artifact", "A file a workflow saves, such as an APK or a test report."),
        term("MCP", "Model Context Protocol: the way PocketIDE gives agents tools such as run_build and show_media."),
        term(
            "Limiter",
            "The part of PocketIDE that queues or pauses work when memory, heat, battery or storage run short.",
        ),
        term("Doctor", "The test that checks an agent really runs on this phone before and after each update."),
        term(
            "Metered network",
            "Mobile data or any network Android marks as paid by use. Only these count toward your daily limit.",
        ),
        term(
            "Foreground service",
            "Work Android keeps running while a notice is shown. PocketIDE uses it while agents work.",
        ),
        term(
            "Two-step sign-in",
            "A second proof, such as a passkey or an authenticator app, after the password. It is the real lock " +
                "on your accounts.",
        ),
        term(
            "Prompt injection",
            "Text in a file, issue or web page that tries to give an agent orders. Review changes before Put on " +
                "main.",
        ),
    )
}
