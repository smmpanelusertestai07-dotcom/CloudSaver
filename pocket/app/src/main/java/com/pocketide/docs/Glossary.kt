package com.pocketide.docs

/** The few words PocketIDE uses, in plain language. */
internal object Glossary {
    val all: List<GlossaryEntry> = listOf(
        term("Cloud computer", "Your GitHub Codespace: a private machine on GitHub's servers, one per project."),
        term("Codespace", "GitHub's name for a cloud computer made from a repository."),
        term("Agent", "An AI that writes and runs code for you: Claude Code, Codex or Antigravity."),
        term("Extension", "An add-on for VS Code. Each agent is its maker's own extension."),
        term("Repository", "A project's folder of code on GitHub, with its full history."),
        term("Private", "Only you, and people you invite, can see it."),
        term("Push", "Sending new commits from the computer to GitHub."),
        term("Branch", "A separate line of work in a repository; the default branch is usually main."),
        term("Core-hour", "One hour of one processor core. An hour on a 2-core computer is 2 core-hours."),
        term("GB-month", "One gigabyte stored for a whole month: how GitHub counts storage."),
        term("Idle", "No activity: no typing, no file changes, no terminal output."),
        term("GitHub Actions", "GitHub's machines that build and test your project from workflow files."),
        term("Settings Sync", "A VS Code feature that copies settings between devices. Keep it off for Codespaces."),
    )
}
