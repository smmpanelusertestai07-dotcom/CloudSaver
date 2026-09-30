package com.pocketide.docs

import com.pocketide.cloudshell.CloudShell

/** Terms, privacy policy and the pointer to the open-source notices. */
internal object Legal {
    const val EFFECTIVE_DATE = "30 Sep 2026"

    /** The notices text, in the APK's assets. */
    const val NOTICES_ASSET = "notices/open-source-notices.txt"

    val terms = section(
        DocsContent.TERMS_ID,
        "Terms of use",
        "Plain terms for using PocketIDE. Effective $EFFECTIVE_DATE.",
        p("Effective $EFFECTIVE_DATE, for PocketIDE 6. The app asks you again when they change."),
        steps(
            "PocketIDE is free and open source under the Apache License 2.0. It is provided as is, without warranty of any " +
                "kind. Keep your own judgement about what you build, run and publish.",
            "There is no PocketIDE account and no server of ours. Your computer is Google Cloud Shell, under your own Google " +
                "account and Google's terms for Google Cloud. The agents work with your own accounts at their companies; those " +
                "companies' terms, plans and prices apply to your use of them.",
            "PocketIDE is not made, endorsed or sponsored by Anthropic, OpenAI, Google, Coder, Microsoft or the Eclipse " +
                "Foundation. Their names and marks are used only to say whose software or service is used.",
            "Agents act on your instructions and can make mistakes. Review their work before you publish it; what you publish " +
                "is your responsibility.",
            "Acceptable use: do not use PocketIDE to break the law, attack or overload other systems, or break the terms of " +
                "Google or of an agent's company. Cloud Shell is for your own interactive work: no coin mining, network " +
                "scanning or tricks to keep it awake.",
            "You can stop at any time: delete your Cloud Shell's home folder, and Settings > Your data on the phone, or " +
                "uninstall the app.",
        ),
        p("Questions: open an issue in PocketIDE's GitHub repository."),
        link("Google Cloud terms", CloudShell.TERMS),
        link("PocketIDE on GitHub", DocLinks.ISSUES),
    )

    val privacy = section(
        DocsContent.PRIVACY_ID,
        "Privacy policy",
        "What data exists, where it is, and who can see it. Effective $EFFECTIVE_DATE.",
        p("Effective $EFFECTIVE_DATE, for PocketIDE 6."),
        p(
            "Who we are: PocketIDE is an open-source app with no server behind it. Its developer receives nothing from it: " +
                "no account, no analytics, no crash reports, no ads, no tracking.",
        ),
        p(
            "On this phone: only PocketIDE's settings, including the Google account's address Cloud Shell opens with, and " +
                "when it was set up and last opened. Android's cloud backup and device transfer are off for PocketIDE.",
        ),
        p(
            "In your Google Cloud Shell: your projects, each agent's chats and sign-in, and each agent's VS Code, in the " +
                "home folder only your Google account opens. Google keeps it under its privacy notice for Google Cloud, and " +
                "deletes it after ${CloudShell.DELETED_AFTER_DAYS} days without use.",
        ),
        p(
            "What else leaves: each agent sends its company what it needs to work (your prompts, and the code and files it " +
                "reads) under your account there and that company's policy; PocketIDE cannot delete their copy. The set-up " +
                "downloads from GitHub (its script and code-server), Open VSX (the agents) and Google (Antigravity's tool), " +
                "and the app reads each agent's icon from Open VSX; they see the request, like any website does.",
        ),
        p(
            "If an error stops the app, PocketIDE keeps what it was on this phone and shows it, with a button to copy it for " +
                "a report. It goes nowhere unless you share it.",
        ),
        p(
            "Permissions: internet and biometrics (App lock). No storage, camera, microphone, location, contacts, notifications " +
                "or accounts access: the Google account comes from Android's own chooser, which gives PocketIDE only the one you pick.",
        ),
        p(
            "Deleting: in Cloud Shell, sudo rm -rf \$HOME and a restart delete your home folder; Settings > Your data deletes " +
                "PocketIDE's data on the phone, and uninstalling does too. What an agent's company keeps is deleted with that company.",
        ),
        p("Children: PocketIDE is not directed at children. Google and the agents' companies each set their own minimum age."),
        p("Contact: open an issue in PocketIDE's GitHub repository. Never post a secret or personal data there."),
        link("Google Cloud privacy notice", CloudShell.PRIVACY),
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
        ),
        p(
            "Installed in your Cloud Shell by the set-up, not part of the app: code-server (MIT) with VS Code's open-source " +
                "code (MIT), and the agents' extensions under their makers' licences.",
        ),
        p("The full notices, with copyright lines and licence texts:"),
    )

    val all = listOf(terms, privacy, notices)
}
