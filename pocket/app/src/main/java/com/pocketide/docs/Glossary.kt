package com.pocketide.docs

/** The few words PocketIDE uses, in plain language. */
internal object Glossary {
    val all: List<GlossaryEntry> = listOf(
        term("Computer", "Ubuntu Linux inside PocketIDE, on this phone, where the agents work."),
        term("Ubuntu", "The Linux system the computer runs; 26.04 is a long-term support (LTS) release."),
        term("LTS", "Long-term support: Ubuntu publishes security fixes for five years."),
        term("PRoot", "The program that runs Ubuntu inside an app without root or a virtual machine."),
        term("code-server", "VS Code for the web, running on the computer; the agents' screens live in it."),
        term("Agent", "An AI that writes and runs code for you: Claude Code, Codex, Antigravity, or one you add."),
        term("Extension", "An add-on for VS Code. Each agent is its maker's own extension."),
        term("Open VSX", "The open registry of VS Code extensions, where PocketIDE gets the agents."),
        term("Verified publisher", "A publisher whose ownership Open VSX has checked."),
        term("Terminal", "The computer's command line."),
        term("Key", "A setting a program reads from its environment, like an API key or a token."),
        term("Project", "A folder in ~/projects on the computer."),
        term("Repository", "A project's code and full history, on GitHub or on the computer."),
    )
}
