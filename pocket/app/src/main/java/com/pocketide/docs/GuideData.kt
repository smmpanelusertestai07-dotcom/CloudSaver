package com.pocketide.docs

/** Guide, part 2: your data, deleting it, getting it back, and the key that protects it. */
internal object GuideData {

    val yourData = section(
        "your-data",
        "Your data",
        "Where each thing lives, how big it is, and the Your data screen.",
        table(
            listOf("Data", "Phone", "Drive (encrypted)", "GitHub (private)"),
            row("Code and history", "Session folders", "—", "Main copy"),
            row("Build setup", "Yes", "—", "Yes"),
            row("Build outputs", "Last 3", "—", "Actions artifacts, 7 days"),
            row("Dependencies and caches", "Yes (rebuildable)", "—", "Never"),
            row("Project secrets (.env)", "Yes", "Yes", "Never (builds get them as GitHub Secrets, on your tap)"),
            row("Chats and sessions", "Last 30 days", "All", "Never"),
            row("Memory, instructions, settings", "Yes", "Yes", "Never"),
            row("Agent sign-ins", "Only here", "Never", "Never"),
            row("GitHub token", "Keystore, never in Linux", "Never", "—"),
            row("The key", "Full key, in the Keystore", "Half D", "Half G (pocketide-keyring)"),
            row("The computer", "About 2.5 GB", "Never", "Never"),
        ),
        p(
            "Sizes: a project's source is usually a few MB, web dependencies a few hundred, and a long chat a " +
                "few hundred MB before compression.",
        ),
        p(
            "The Your data screen lists everything by type and size, largest first, with Secrets masked until " +
                "your fingerprint reveals them. There you edit agent memory, remove a chat's media, delete, or " +
                "Move to another Google account.",
        ),
        p(
            "A reopened chat can always be read in Chats and in its agent's screen (Codex and Antigravity on a " +
                "new phone: $BEING_TESTED). Videos and big downloads wait for Wi-Fi unless you allow mobile data.",
        ),
        link("Your Google storage", DocLinks.GOOGLE_STORAGE),
    )

    val withoutTheApp = section(
        "without-the-app",
        "Seeing and deleting without the app",
        "What you can check or remove from a browser, even with no phone at hand.",
        table(
            listOf("You want to", "How"),
            row(
                "See how much space it uses",
                "drive.google.com (Desktop site on a phone) → Settings → Manage apps → PocketIDE's hidden " +
                    "app data.",
            ),
            row("Delete it all", "Same place → Options → Delete hidden app data, then Disconnect from Drive."),
            row("See single files", "Not possible: they are hidden and encrypted. Use Your data."),
            row(
                "Remove GitHub access",
                "github.com → Settings → Applications: uninstall PocketIDE, then revoke it. Delete " +
                    "pocketide-keyring if you want.",
            ),
            row("Remove Google access", "myaccount.google.com → Security → Third-party connections → PocketIDE."),
        ),
        info("The Drive Android app has no Manage apps; use the Drive website. $LABELS_NOTE"),
        link("Drive settings", DocLinks.DRIVE_SETTINGS),
        link("Google's help on disconnecting apps", DocLinks.DRIVE_DISCONNECT_HELP),
        link("GitHub: installed apps", DocLinks.GITHUB_INSTALLATIONS),
        link("GitHub: authorized apps", DocLinks.GITHUB_AUTHORIZATIONS),
        link("Google: third-party connections", DocLinks.GOOGLE_CONNECTIONS),
    )

    val deleting = section(
        "deleting",
        "Deleting and Recently deleted",
        "One place to undo a delete, real erasing after 30 days, and what an uninstall leaves behind.",
        table(
            listOf("When you", "What happens"),
            row(
                "Delete a chat",
                "The phone copy goes now. In Drive the file gets a \"deleted on\" date; no copy is made.",
            ),
            row("Want it back", "Chats → Recently deleted → Restore, within 30 days. Drive's Trash is not used."),
            row("Wait 30 days", "A daily job erases the file from Drive for good."),
            row("Tap Delete forever", "The chat (or all of them) is erased from Drive now."),
            row("Reinstall", "The 30-day count continues from the original date, which is kept in Drive."),
            row("Want everything gone", "Your data → Delete everything."),
        ),
        warn(
            "If you uninstall within the 30 days, nothing can run, so the marked files stay in Drive " +
                "(encrypted and unreadable) until you reinstall or use Drive → Manage apps → Delete hidden app " +
                "data.",
        ),
        p(
            "The phone's own clean-up never counts as a delete.",
        ),
    )

    val recovery = section(
        "recovery",
        "Recovery, a new phone, another Google account",
        "How everything comes back, and what happens if one place is lost.",
        steps(
            "Install PocketIDE and sign in to GitHub and Google. The key is rebuilt from its two halves.",
            "Read the restore plan: what comes now (memory, settings, secrets, recent chats), what waits until " +
                "opened, your network and free space.",
            "Choose Wi-Fi only, or mobile data up to a size you set.",
            "Open a project to clone its code. Sign in to each agent again.",
        ),
        p(
            "Move to another Google account (Your data) copies one file at a time, makes a new Half D there, " +
                "checks everything, then asks before erasing the old copy.",
        ),
        table(
            listOf("If you lose", "Result"),
            row("The phone, or the app", "Drive and GitHub rebuild the key. Everything comes back."),
            row(
                "GitHub, while the phone has the app",
                "Safe: the phone has the key. Connect GitHub again and new halves are made.",
            ),
            row("Your Google account", "The Drive data goes with it. Code on GitHub is not affected."),
            row("The phone and GitHub together", "The chats cannot be opened, unless you saved a key copy."),
        ),
        p(
            "One phone at a time: a second phone takes over and the first locks; its offline work becomes a " +
                "conflict copy.",
        ),
    )

    val theKey = section(
        "the-key",
        "The key",
        "Split in two halves, nothing to remember, and why sharing is risky.",
        p(
            "A random key (age, an open standard) encrypts every file in Drive. It is split into two random " +
                "halves: Half D in Drive's hidden folder, Half G in your private repo pocketide-keyring. Either " +
                "half alone is useless. The full key stays in this phone's Keystore, which the computer cannot read.",
        ),
        p(
            "Why two places: a new phone rebuilds the key with nothing to remember, yet neither Google nor " +
                "GitHub alone can open your chats. Turn on 2-step sign-in for both.",
        ),
        link("Google: 2-step sign-in", DocLinks.GOOGLE_SECURITY),
        link("GitHub: 2-step sign-in", DocLinks.GITHUB_SECURITY),
        table(
            listOf("If you share", "What happens"),
            row("The Drive vault", "Not possible: the hidden folder cannot be shared."),
            row(
                "pocketide-keyring (public, or a collaborator)",
                "Half the lock is given away. The app makes a new key and tells you. Never share it.",
            ),
            row("Your unlocked phone", "The app lock still protects PocketIDE."),
            row("A project repo", "Only code. The check-post keeps chats and keys out."),
            row("Your Google or GitHub password", "One half. Use 2-step sign-in."),
        ),
        p(
            "Extra password (Advanced, off) wraps Half G, so even someone with both accounts cannot read the " +
                "chats. It is asked only on a new phone; forget it and the chats are lost.",
        ),
        p(
            "Save a key copy (Advanced) shows the key once, for a password manager. It covers losing the phone " +
                "and GitHub together. Anyone with the copy and your Drive can read your chats.",
        ),
    )

    val all = listOf(yourData, withoutTheApp, deleting, recovery, theKey)
}
