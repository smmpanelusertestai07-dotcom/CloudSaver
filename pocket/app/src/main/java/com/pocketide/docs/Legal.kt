package com.pocketide.docs

/** Terms, privacy policy and the pointer to the open-source notices. */
internal object Legal {
    const val EFFECTIVE_DATE = "26 Sep 2026"

    /** The notices text, in the APK's assets. */
    const val NOTICES_ASSET = "notices/open-source-notices.txt"

    val terms = section(
        DocsContent.TERMS_ID,
        "Terms of use",
        "Plain terms for using PocketIDE. Effective $EFFECTIVE_DATE.",
        p("Effective $EFFECTIVE_DATE, for PocketIDE 4. The app asks you again when they change."),
        steps(
            "PocketIDE is free and open source under the Apache License 2.0. It is provided as is, without warranty of any " +
                "kind. Keep your own judgement about what you build, run and publish.",
            "There is no PocketIDE account and no server of ours. The app works with your own accounts: GitHub, and each " +
                "agent's company. Their terms, plans and prices apply to your use of them, including Codespaces hours and " +
                "Actions minutes, which GitHub bills to you if you go past the free allowance with a payment method set.",
            "PocketIDE is not made, endorsed or sponsored by GitHub, Microsoft, Anthropic, OpenAI or Google. Their names and " +
                "marks are used only to say whose software or service is used.",
            "Agents act on your instructions and can make mistakes. Review their work before you merge or publish it; what you " +
                "publish is your responsibility.",
            "Acceptable use: do not use PocketIDE, or the computers and builds it starts, to break the law, attack or overload " +
                "other systems, mine cryptocurrency, run a general-purpose server, make extra accounts for more free usage, or " +
                "otherwise break the terms of GitHub or an agent's company.",
            "You can stop at any time: Settings > Your data > Leave PocketIDE.",
        ),
        link("GitHub Terms of Service", DocLinks.GITHUB_TERMS),
        link("GitHub terms for Codespaces and Actions", DocLinks.GITHUB_ADDITIONAL_TERMS),
        p("Questions: open an issue in PocketIDE's GitHub repository."),
        link("PocketIDE on GitHub", DocLinks.ISSUES),
    )

    val privacy = section(
        DocsContent.PRIVACY_ID,
        "Privacy policy",
        "What data exists, where it is, and who can see it. Effective $EFFECTIVE_DATE.",
        p("Effective $EFFECTIVE_DATE, for PocketIDE 4."),
        p(
            "Who we are: PocketIDE is an open-source app with no server behind it. Its developer receives nothing from it: " +
                "no account, no analytics, no crash reports, no ads, no tracking.",
        ),
        p(
            "What PocketIDE can reach: through the GitHub App you approve, the repositories you choose, your codespaces, your " +
                "Actions runs and your plan's usage. It uses them only to show them to you and to do what you ask.",
        ),
        p(
            "Where your data is: your code in your GitHub repositories; each cloud computer, with its chats and agent sign-ins, " +
                "in your GitHub Codespaces; build logs in GitHub Actions. On the phone: your GitHub sign-in, sealed with an " +
                "Android Keystore key, the computer page's own GitHub sign-in, and your settings. Android's cloud backup is off " +
                "for PocketIDE.",
        ),
        p(
            "Who can see it: you. GitHub hosts it under its own privacy statement. Each AI company receives what its agent sends " +
                "(your prompts, and the code and files the agent reads) and keeps it under its own policy; PocketIDE cannot " +
                "delete their copy.",
        ),
        p(
            "Permissions: only those listed under Permissions. No storage, camera, microphone, location, contacts or accounts " +
                "access.",
        ),
        p(
            "Deleting: Settings > Your data deletes the phone's part, and each cloud computer with its chats. Uninstalling " +
                "removes the phone's part too. Your GitHub data stays until you delete it on GitHub.",
        ),
        p("Children: PocketIDE is not directed at children. It needs a GitHub account and AI accounts, each with its own minimum age."),
        link("GitHub privacy statement", DocLinks.GITHUB_PRIVACY),
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
            row("GitHub mark (Octicons)", "MIT License"),
            row("Visual Studio Code mark (Codicons)", "CC BY 4.0"),
        ),
        p("The full notices, with copyright lines and licence texts:"),
    )

    val all = listOf(terms, privacy, notices)
}
