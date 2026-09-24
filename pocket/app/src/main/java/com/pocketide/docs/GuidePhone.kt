package com.pocketide.docs

/** Guide, part 4: builds on GitHub Actions, phone conditions, limits, fixing problems and permissions. */
internal object GuidePhone {

    private const val AS_OF = "as of ${DocLinks.CHECKED_ON}"

    val githubActions = section(
        "github-actions",
        "GitHub Actions: plans and limits",
        "What your GitHub plan includes for builds, what extra use costs, and why PocketIDE uses only Actions.",
        info(
            "Prices and limits below are GitHub's, $AS_OF, in US dollars (your bank converts them). GitHub can " +
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
            "Windows and macOS minutes cost more than Linux ones; a macOS minute costs about ten times a Linux " +
                "one ($AS_OF).",
            "Pay-as-you-go, only with a payment method: Linux $0.006 a minute, Linux arm64 $0.005, Windows " +
                "$0.010, macOS $0.062 ($AS_OF).",
            "Without a payment method, Actions stop at the limit: no surprise bill. Minutes reset each month.",
        ),
        p(
            "The Usage screen shows your real plan, this month's minutes by system, and an estimate of builds " +
                "left. Free with private repos covers roughly 200 to 400 Android release builds a month, or 13 " +
                "to 20 iOS builds ($AS_OF; build times vary).",
        ),
        tip(
            "Recommendation: Free with private repos for your own apps; make open-source projects public, " +
                "where Actions is free but the code is visible. For many iOS builds, add paid minutes on GitHub.",
        ),
        p(
            "Why only Actions: it already holds your code, so no new account or company sees it. PocketIDE's " +
                "ready templates run only when started, and results come back into Media.",
        ),
        p(
            "Free computers with no sign-in, or no data on their servers, do not exist: any remote computer is " +
                "somebody's server. Using build minutes for anything but building and testing breaks the " +
                "providers' terms.",
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
            row("Restart, or the app is closed", "Sync finishes later from an encrypted queue."),
            row("Google or GitHub access removed", "The app locks and uploads after you reconnect."),
        ),
        p(
            "When Android restricts the app, or after you force-stop it, the daily job runs late or not until " +
                "you open PocketIDE. So the 30-day erase and the 90-day computer rule can happen later, never " +
                "earlier.",
        ),
        p(
            "Some phone makers stop background apps on their own. On realme, once: Settings → Battery → App " +
                "battery management → PocketIDE. Turn on Allow auto-launch and Allow background activity, turn " +
                "off Optimize battery use, and keep it out of App quick freeze. $LABELS_NOTE",
        ),
        info(
            "These are PocketIDE 3.0's own heat and battery rules ($AS_OF). The phone strip on Home shows the " +
                "live state.",
        ),
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
            "The phone has an arm64 processor. Programs and toolchains made only for x86-64 computers or for " +
                "macOS cannot run on it, so that work goes to GitHub Actions.",
        ),
        table(
            listOf("Not on the phone", "Instead"),
            row("Docker (needs a real Linux kernel with containers)", "A Docker workflow on GitHub Actions"),
            row(
                "Android emulators (need an x86-64 computer with hardware virtualisation)",
                "The emulator workflow on Actions; its video and screenshots come back to Media",
            ),
            row(
                "iOS and macOS builds (need a Mac)",
                "The macOS runner on Actions. Installing on an iPhone also needs an Apple developer account",
            ),
            row("Windows builds and x86-64 programs", "The Windows or Linux runners on Actions"),
            row(
                "Computer use (an AI clicking a desktop)",
                "Browser automation for web apps; Android UI tests on the Actions emulator",
            ),
            row("Local AI models good enough for agent work", "The agents' own cloud models"),
        ),
        p(
            "The agents need the internet, because their models are remote. The computer, your files and " +
                "Preview work offline, and sync catches up later.",
        ),
    )

    val somethingBreaks = section(
        "if-something-breaks",
        "If something breaks",
        "What to try, in order, what each message means, and what survives each step.",
        steps(
            "Reload the agent's screen. It takes seconds.",
            "Stop the agent (its menu → Stop agent) and open it again. Its sessions are kept.",
            "Reset computer (Settings → Advanced). The computer is rebuilt; projects, chats, memory, settings " +
                "and agent sign-ins are kept. It is a big download, so use Wi-Fi.",
        ),
        p("Go to the next step only if the one before did not help."),
        table(
            listOf("You see", "What to do"),
            row(
                "Claude Code asks you to sign in, then stops",
                "The account has no Claude Code plan: Pro, Max, Team, Enterprise or Console.",
            ),
            row(
                "Antigravity still asks to sign in after you did",
                "Stop it and open it again; it reads the sign-in at start.",
            ),
            row(
                "Stopped at the check-post",
                "The finding names the file. Remove it from the session, then push again.",
            ),
            row("Google storage full", "New chats wait on the phone. Free space or delete old chats; sync catches up."),
            row("GitHub or Google access removed", "Reconnect on the lock screen. Nothing waiting is lost."),
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
        p("Dead ends, by design:"),
        bullets(
            "Signing in to GitHub inside the computer: agents commit, and PocketIDE pushes for them.",
            "Extensions from Microsoft's Marketplace: only Open VSX.",
            "Opening Drive's hidden files: they are encrypted; use Your data.",
        ),
        p("Defaults worth keeping:"),
        bullets(
            "Big downloads on Wi-Fi only: set-up and updates are large.",
            "Private repos for your own apps.",
            "The AI companies' training switches off (Privacy).",
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
