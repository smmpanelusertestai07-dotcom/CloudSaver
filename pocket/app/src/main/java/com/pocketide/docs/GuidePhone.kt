package com.pocketide.docs

/** Guide, part 4: builds on GitHub Actions, phone conditions, limits, fixing problems and permissions. */
internal object GuidePhone {

    private const val AS_OF = "as of ${DocLinks.CHECKED_ON}"

    val githubActions = section(
        "github-actions",
        "GitHub Actions: plans and limits",
        "What your GitHub plan includes for builds, what extra use costs, and why PocketIDE uses only Actions.",
        info(
            "Prices and limits below are GitHub's, $AS_OF, in US dollars. GitHub can " +
                "change them; its pages are linked below.",
        ),
        table(
            listOf("Plan", "Price", "Private repos, each month", "Public repos"),
            row("Free", "$0", "2,000 minutes, 500 MB storage", "Free and unlimited on standard runners"),
            row("Pro (personal)", "$4 a month", "3,000 minutes, 1 GB", "Free"),
            row("Team", "$4 per user a month (first year)", "3,000 minutes, 2 GB", "Free"),
            row("Enterprise", "$21 per user a month (first year)", "50,000 minutes, 50 GB", "Free"),
        ),
        bullets(
            "A macOS minute costs about ten times a Linux one, and Windows costs more too ($AS_OF).",
            "Pay-as-you-go, only with a payment method: Linux $0.006 a minute, Linux arm64 $0.005, Windows " +
                "$0.010, macOS $0.062 ($AS_OF).",
            "Without a payment method, Actions stop at the limit: no surprise bill. Minutes reset each month.",
        ),
        p(
            "The Usage screen shows your real plan, minutes used and builds left. Free with private repos " +
                "covers roughly 200 to 400 Android builds a month, or 13 to 20 iOS builds ($AS_OF).",
        ),
        tip("Recommendation: Free with private repos; open source in public repos, where Actions is free."),
        p(
            "Why only Actions: it already holds your code, so no new company sees it, and results come back " +
                "into Media. Free computers with no sign-in do not exist, and using build minutes for anything " +
                "but builds and tests breaks the providers' terms.",
        ),
        link("GitHub pricing", DocLinks.GITHUB_PRICING),
        link("GitHub Actions billing", DocLinks.ACTIONS_BILLING),
        link("Actions runner pricing", DocLinks.ACTIONS_RATES),
    )

    val conditions = section(
        "conditions",
        "Battery, background and other conditions",
        "What PocketIDE does when Android saves power, the network changes or the phone gets hot.",
        table(
            listOf("Condition", "What happens"),
            row("Screen off (Doze)", "While agents work, a visible notice keeps the computer and its network running."),
            row("Battery Saver", "Work continues; background sync may be late, then catches up."),
            row("Data Saver", "Big downloads wait for Wi-Fi ($BEING_TESTED)."),
            row("Battery set to Restricted", "A banner explains the fix; work pauses safely."),
            row("Offline", "Agents wait; sync resumes by itself."),
            row(
                "Online, but downloads fail",
                "Check network names the usual cause: strict Private DNS, a VPN, a sign-in page, a filter or Data Saver.",
            ),
            row("Restart, or the app is closed", "Sync finishes later from an encrypted queue."),
        ),
        p(
            "If Android restricts the app, or you force-stop it, the daily job runs late, so the 30-day erase " +
                "and the 90-day computer rule can happen later, never earlier.",
        ),
        p(
            "Some phone makers stop background apps. On realme, once: Settings → Battery → App battery " +
                "management → PocketIDE: allow auto-launch and background activity, turn off optimisation, and " +
                "keep it out of App quick freeze. $LABELS_NOTE",
        ),
        info("PocketIDE 3.0's own heat and battery rules ($AS_OF). Home's phone strip shows the live state."),
        table(
            listOf("Heat or battery", "What PocketIDE does"),
            row("Moderate heat", "No new agents start"),
            row("Severe heat", "Work pauses"),
            row("Critical heat", "A safe stop"),
            row("Battery at 20% or less", "No new heavy work"),
            row("Battery at 10% or less", "Pause after the current step, then sync"),
            row("Battery at 5% or less", "A safe stop"),
            row("Charging", "Work resumes"),
        ),
        link("Steps for realme phones", DocLinks.REALME_STEPS),
        link("Android: power management", DocLinks.ANDROID_POWER),
    )

    val limits = section(
        "limits",
        "Limits: what a phone cannot do",
        "What is impossible on the phone, and where that work goes instead.",
        p(
            "The phone has an arm64 processor, so tools made only for x86-64 or macOS run on GitHub Actions instead.",
        ),
        table(
            listOf("Not on the phone", "Instead"),
            row("Docker (needs container features Android does not give apps)", "A Docker workflow on Actions"),
            row(
                "Android emulators (need x86-64 hardware virtualisation)",
                "The emulator workflow; its video comes to Media",
            ),
            row(
                "iOS and macOS builds (need a Mac)",
                "The macOS runner. An iPhone install also needs an Apple developer account",
            ),
            row("Windows builds and x86-64 programs", "The Windows or Linux runners"),
            row("Computer use (an AI clicking a desktop)", "Browser automation; Android UI tests on the emulator"),
            row("Local AI models good enough for agent work", "The agents' own cloud models"),
        ),
        p(
            "Offline, the computer, your files and Preview still work; the agents wait and sync catches up later.",
        ),
    )

    val somethingBreaks = section(
        "if-something-breaks",
        "If something breaks",
        "What to try, in order, what each message means, and what survives each step.",
        p(FixLadder.helpIntro),
        steps(*FixLadder.rungs.map(FixLadder::helpStep).toTypedArray()),
        table(
            listOf("You see", "What to do"),
            row(
                "Claude Code asks you to sign in, then stops",
                "The account has no Claude Code plan: Pro, Max, Team, Enterprise or Console.",
            ),
            row(
                "At sign-in: Error 400 invalid_request, 401 invalid_client or Invalid code verifier",
                "The link was changed on the way. Start a fresh sign-in.",
            ),
            row(
                "Antigravity still asks to sign in after you did",
                "Stop it and open it again; it reads the sign-in at start.",
            ),
            row(
                "Stopped at the check-post",
                "The finding names the file. Remove it from the session, then push again.",
            ),
            row("Google storage full", "New chats wait on the phone. Free space; sync catches up."),
        ),
        table(
            listOf("After", "Code", "Chats and media", "Memory, Variables, Secrets", "Agent sign-ins", "The computer"),
            row("Restarting the phone", "Kept", "Kept", "Kept", "Kept", "Kept"),
            row("Reset computer", "Kept", "Kept", "Kept", "Kept", "Rebuilt"),
            row(
                "Reinstalling, or a new phone",
                "From GitHub", "From Drive", "From Drive", "Sign in again", "Built again",
            ),
        ),
        warn("Work not yet pushed or synced is only on the phone. Let sync finish before you uninstall."),
        bullets(
            "Dead ends: signing in to GitHub inside the computer (PocketIDE pushes for agents), Marketplace " +
                "extensions, and opening Drive's hidden files (use Your data).",
            "Defaults worth keeping: big downloads on Wi-Fi only, private repos, and the training switches off.",
        ),
    )

    val permissions = section(
        "permissions",
        "Permissions",
        "Each permission PocketIDE declares, why, and what it never asks for.",
        table(
            listOf("Permission", "Why"),
            row("INTERNET", "GitHub, Drive, Open VSX, Ubuntu updates, and each agent's own traffic."),
            row("ACCESS_NETWORK_STATE", "Tells Wi-Fi from mobile data, and offline from signed out."),
            row("FOREGROUND_SERVICE", "Keeps the computer running while agents work, with a notice and a Stop button."),
            row("FOREGROUND_SERVICE_SPECIAL_USE", "The service type Android requires for running a computer."),
            row("FOREGROUND_SERVICE_DATA_SYNC", "So a long upload to Drive is not cut off halfway."),
            row(
                "POST_NOTIFICATIONS",
                "Asked once: the running notice, finished builds, limits reached, access removed, new agents.",
            ),
            row("WAKE_LOCK", "Keeps the processor awake during a task with the screen off."),
            row("USE_BIOMETRIC", "The app lock and revealing Secrets. The phone does the check."),
            row("REQUEST_INSTALL_PACKAGES", "Installs an APK you built, only when you tap. Android asks too."),
            row(
                "RECEIVE_BOOT_COMPLETED",
                "Lets a pending sync finish after a restart. The computer does not start at boot.",
            ),
        ),
        p(
            "Never asked: storage or photos (the photo picker needs no permission), camera, microphone, " +
                "location, contacts, SMS, calls, calendar, accounts, your list of apps, or a battery exemption.",
        ),
    )

    val all = listOf(githubActions, conditions, limits, somethingBreaks, permissions)
}
