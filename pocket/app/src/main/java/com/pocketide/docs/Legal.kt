package com.pocketide.docs

/** Terms, privacy policy and the pointer to the open-source notices. */
internal object Legal {
    const val EFFECTIVE_DATE = "29 Sep 2026"

    /** The notices text, in the APK's assets. */
    const val NOTICES_ASSET = "notices/open-source-notices.txt"

    val terms = section(
        DocsContent.TERMS_ID,
        "Terms of use",
        "Plain terms for using PocketIDE. Effective $EFFECTIVE_DATE.",
        p("Effective $EFFECTIVE_DATE, for PocketIDE 5. The app asks you again when they change."),
        steps(
            "PocketIDE is free and open source under the Apache License 2.0. It is provided as is, without warranty of any " +
                "kind. Keep your own judgement about what you build, run and publish.",
            "There is no PocketIDE account and no server of ours. The agents work with your own accounts at their companies; " +
                "those companies' terms, plans and prices apply to your use of them.",
            "PocketIDE is not made, endorsed or sponsored by Anthropic, OpenAI, Google, Canonical, Coder, Microsoft or the " +
                "Eclipse Foundation. Their names and marks are used only to say whose software or service is used.",
            "Agents act on your instructions and can make mistakes. Review their work before you publish it; what you publish " +
                "is your responsibility.",
            "Acceptable use: do not use PocketIDE to break the law, attack or overload other systems, or break the terms of an " +
                "agent's company.",
            "You can stop at any time: Settings > Your data > Delete everything, or uninstall the app.",
        ),
        p("Questions: open an issue in PocketIDE's GitHub repository."),
        link("PocketIDE on GitHub", DocLinks.ISSUES),
    )

    val privacy = section(
        DocsContent.PRIVACY_ID,
        "Privacy policy",
        "What data exists, where it is, and who can see it. Effective $EFFECTIVE_DATE.",
        p("Effective $EFFECTIVE_DATE, for PocketIDE 5."),
        p(
            "Who we are: PocketIDE is an open-source app with no server behind it. Its developer receives nothing from it: " +
                "no account, no analytics, no crash reports, no ads, no tracking.",
        ),
        p(
            "Where your data is: everything is on this phone, in PocketIDE's private storage: the computer with your projects, " +
                "each agent's sign-in, settings and chats, your keys (sealed with a key in the phone's secure hardware) and " +
                "your settings. Android's cloud backup and device transfer are off for PocketIDE.",
        ),
        p(
            "What leaves the phone: each agent sends its company what it needs to work (your prompts, and the code and files " +
                "it reads) under your account there and that company's policy; PocketIDE cannot delete their copy. Downloads " +
                "come from Ubuntu, GitHub (code-server's releases), Open VSX and Google (Antigravity's tool); they see the " +
                "request, like any website does.",
        ),
        p(
            "Permissions: internet, network state, notifications, the foreground service that keeps the computer running, and " +
                "biometrics for App lock. No storage, camera, microphone, location, contacts or accounts access.",
        ),
        p(
            "Deleting: Settings > Your data > Delete everything deletes all of it from the phone; uninstalling does too. " +
                "What an agent's company keeps is deleted with that company.",
        ),
        p("Children: PocketIDE is not directed at children. The agents' companies each set their own minimum age."),
        p("Contact: open an issue in PocketIDE's GitHub repository. Never post a secret or personal data there."),
        link("PocketIDE on GitHub", DocLinks.ISSUES),
    )

    val notices = section(
        DocsContent.NOTICES_ID,
        "Open-source licences",
        "The software inside PocketIDE, and each licence.",
        table(
            listOf("Part", "Licence"),
            row("PocketIDE", "Apache License 2.0"),
            row("AndroidX and Jetpack, Material icons", "Apache License 2.0"),
            row("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache License 2.0"),
            row("OkHttp and Okio", "Apache License 2.0"),
            row("Haze", "Apache License 2.0"),
            row("PRoot", "GNU General Public License, version 2"),
            row("talloc", "GNU Lesser General Public License, version 3 or later"),
            row("libandroid-shmem", "BSD 3-Clause licence"),
        ),
        p("PRoot's exact source, and talloc's, is published beside each release of PocketIDE."),
        p(
            "Downloaded at set-up, not part of the app: Ubuntu (its packages' own licences), code-server (MIT) with VS Code's " +
                "open-source code (MIT), and the agents' extensions under their makers' licences.",
        ),
        p("The full notices, with copyright lines and licence texts:"),
    )

    val all = listOf(terms, privacy, notices)
}
