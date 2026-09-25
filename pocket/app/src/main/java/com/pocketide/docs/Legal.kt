package com.pocketide.docs

/** Terms, privacy policy and the pointer to the open-source notices. Not counted in the guide's word budget. */
internal object Legal {

    const val EFFECTIVE_DATE = "24 Sep 2026"

    /** The notices text, in the APK's assets. */
    const val NOTICES_ASSET = "notices/open-source-notices.txt"

    val terms = section(
        "terms",
        "Terms of use",
        "Plain terms for using PocketIDE. Effective $EFFECTIVE_DATE.",
        p("Effective $EFFECTIVE_DATE, for PocketIDE 3.0. They do not change within this version."),
        steps(
            "PocketIDE is free and open source under the Apache License 2.0. It is provided as is, without " +
                "warranty of any kind. Keep your own judgement about what you build, run and publish.",
            "There is no PocketIDE account and no server of ours. The app works with your own accounts: " +
                "GitHub, Google, and each agent's company. Their terms, plans and prices apply to your use of " +
                "them, including GitHub Actions minutes billed to you.",
            "PocketIDE is not made, endorsed or sponsored by Anthropic, OpenAI, Google, GitHub, Microsoft, " +
                "Canonical, Coder or the Eclipse Foundation. Their names are used only to say whose software " +
                "is run.",
            "Agents act on your instructions and can make mistakes. Their work stays on its session's branch " +
                "until you choose Put on main. Review it first; what you publish is your responsibility.",
            "Extra agents are third-party software. The app shows the publisher and its label before you add " +
                "one; adding it is your choice.",
            "Acceptable use: do not use PocketIDE, or the build minutes it starts, to break the law, attack " +
                "or overload other systems, mine cryptocurrency, or break the terms of GitHub, Google or an " +
                "agent's company.",
            "The parts PocketIDE downloads (Ubuntu, the agents, extensions, tools) keep their own licences, " +
                "listed in the open-source notices.",
            "You can stop at any time: uninstall the app and follow Seeing and deleting without the app.",
        ),
        p("Questions: open an issue in PocketIDE's GitHub repository."),
        link("PocketIDE on GitHub", DocLinks.ISSUES),
    )

    val privacyPolicy = section(
        "privacy-policy",
        "Privacy policy",
        "What data exists, where it is, and who can see it. Effective $EFFECTIVE_DATE.",
        p("Effective $EFFECTIVE_DATE, for PocketIDE 3.0."),
        p(
            "Who we are: PocketIDE is an open-source app with no company server behind it. Its developer " +
                "receives nothing from the app: no account, no analytics, no crash reports, no ads, no tracking.",
        ),
        p(
            "What data exists and where: your code is in your GitHub repositories. Your chats, agent memory " +
                "and instructions, settings, Variables and Secrets are encrypted on your phone and stored in " +
                "your Google Drive's hidden app folder. Agent sign-ins, caches and the Linux computer stay on " +
                "your phone. The encryption key is kept whole in your phone's Keystore and split into two " +
                "halves, one in Drive and one in your private GitHub repo pocketide-keyring.",
        ),
        p(
            "Who can see it: you. GitHub sees your code, as any repository host does. Google stores files it " +
                "cannot read. Each AI company receives what its agent sends (your prompts, and the code and " +
                "images the agent reads) and keeps it under its own policy. PocketIDE cannot delete their copy.",
        ),
        p(
            "Encryption: vault files are encrypted on the phone with age (X25519) before upload. Tokens and " +
                "keys on the phone are sealed by the Android Keystore. Android's own cloud backup is turned " +
                "off for this app.",
        ),
        p(
            "Permissions: only those listed under Permissions. No storage, camera, microphone, location, " +
                "contacts or accounts access.",
        ),
        p(
            "Deletion: delete one chat or everything in the app. Deleted chats stay in Recently deleted for " +
                "30 days and are then erased from Drive; Delete forever erases them at once. Uninstalling " +
                "removes only the phone's copy. Your Drive and GitHub data can be removed without the app, as " +
                "Seeing and deleting without the app explains.",
        ),
        p(
            "Children: PocketIDE is not directed at children. It needs accounts with GitHub, Google and AI " +
                "companies, each with its own minimum age.",
        ),
        p("Contact: open an issue in PocketIDE's GitHub repository. Never post a secret or personal data there."),
        link("PocketIDE on GitHub", DocLinks.ISSUES),
    )

    val openSource = section(
        "open-source",
        "Open-source notices",
        "The software PocketIDE carries or downloads, and each licence.",
        p(
            "PocketIDE's own code is under the Apache License 2.0. It carries PRoot and its libraries, and " +
                "downloads Ubuntu, code-server, the agents and their extensions at run time. Each part keeps its " +
                "own licence.",
        ),
        table(
            listOf("Part", "Licence"),
            row("PocketIDE", "Apache License 2.0"),
            row("PRoot", "GPL-2.0"),
            row("talloc", "LGPL-3.0-or-later"),
            row("libandroid-shmem", "BSD 3-Clause"),
            row("AndroidX and Jetpack, Material icons", "Apache License 2.0"),
            row("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache License 2.0"),
            row("OkHttp and Okio", "Apache License 2.0"),
            row("Bouncy Castle", "MIT License"),
            row("Eclipse JGit", "EDL-1.0 (BSD-3-Clause)"),
            row("Google Play services", "Google APIs Terms of Service"),
            row("Ubuntu (downloaded)", "Each package keeps its own licence"),
            row("code-server, Code - OSS and xterm.js (downloaded)", "MIT licence"),
            row("The agents and their extensions (downloaded)", "Each publisher's terms"),
        ),
        p(
            "The full notices, with copyright lines, ship inside the app as open-source-notices.txt and in the " +
                "source of each release. The GPL source for each release is published beside it.",
        ),
        link("PocketIDE releases and source", DocLinks.RELEASES),
    )

    val all = listOf(terms, privacyPolicy, openSource)
}
