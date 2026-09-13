package com.pocketide;

/**
 * Everything the app says about itself: what it is for, what it is not, what it asks of the
 * phone, and what it will and will not do with what it finds there.
 *
 * It lives in one file rather than scattered through the screens for a reason that is easy to
 * forget until it bites: a claim in a FAQ and the code that makes the claim true drift apart
 * silently. Keeping the words together makes them reviewable as a set, and several of the gates
 * in tests/ read this file directly -- the permission list here is checked against the manifest,
 * and the agent facts against the registry.
 *
 * Every sentence is meant to survive being read by someone who does not trust the app yet.
 */
final class Texts {
    private Texts() {}

    // ------------------------------------------------------------------ about

    static final String MISSION_TITLE = "What this is for";

    static final String MISSION =
            "PocketIDE exists for one kind of person: someone who wants to build software and "
                    + "does not own a computer.\n\n"
                    + "Every serious way to work with a coding agent today assumes a laptop. The "
                    + "agent-first desktop apps are desktop-only. The editor integrations are "
                    + "desktop-only. The terminal agents want a machine you can leave running. "
                    + "The phone apps the same companies ship are remote controls — the work "
                    + "still happens on a computer somewhere, and if you do not have one, you "
                    + "have nothing.\n\n"
                    + "This app makes the phone that computer. A real Ubuntu system, a real "
                    + "Visual Studio Code, and whichever coding agent you want, all running on "
                    + "the handset in your pocket. No laptop, no cloud subscription for compute, "
                    + "no remote machine to keep awake.";

    static final String VISION_TITLE = "Where it is going";

    static final String VISION =
            "The app is a host, not a product that has to keep up.\n\n"
                    + "New agent features arrive through the publishers' own extensions, from "
                    + "the Open VSX registry, and update themselves. New editor features arrive "
                    + "with code-server. New languages and tools arrive through apt. None of "
                    + "that needs this app to be updated, which is the point: when a fourth "
                    + "company ships a first-party coding agent, you install it the day it "
                    + "appears rather than waiting for someone to add a button for it.\n\n"
                    + "What this app owns is the part that has to be right and then stay still: "
                    + "getting Linux onto the phone safely, keeping the editor usable at 400 "
                    + "pixels wide, and being honest about what is happening.";

    static final String HOW_TITLE = "How it fits together";

    static final String HOW =
            "Your phone\n"
                    + "  └─ PocketIDE, an Android app\n"
                    + "      └─ Ubuntu 24.04 LTS (ARM64), under PRoot — no root, no virtual machine\n"
                    + "          └─ code-server — Visual Studio Code, MIT licensed\n"
                    + "              └─ extensions from Open VSX — the agents\n\n"
                    + "The agents' models run in their own companies' clouds, as they do "
                    + "everywhere. Your files stay on the phone and edits happen on the phone.";

    // ------------------------------------------------------------------ FAQ

    /** Question, answer, icon. Read top to bottom; the order is the order people ask them. */
    static final String[][] FAQ = {
            {"About",
             "What is this, exactly?",
             "A real Ubuntu Linux system and a real Visual Studio Code, running inside an "
                     + "Android app, with coding agents installed as their publishers' own "
                     + "extensions. Everything runs on the phone."},
            {"About",
             "Do I need a computer?",
             "No. Not for set-up, not for building, not at any point."},
            {"About",
             "Is this a virtual machine or an emulator?",
             "Neither. Ubuntu runs directly on the phone's own kernel through PRoot, with no "
                     + "root and no VM. That is why a phone that is several years old can run "
                     + "it at all."},
            {"About",
             "Where does my code live?",
             "In this app's private storage on the phone. No cloud sync, no backup, no upload. "
                     + "Uninstalling the app deletes all of it."},

            {"Editor",
             "Is this the real VS Code?",
             "It is Code-OSS — the MIT-licensed source of Visual Studio Code — packaged by "
                     + "Coder as code-server. Editor, terminal, debugger, git, search, "
                     + "extensions and settings all work."},
            {"Editor",
             "Do I need a Microsoft licence or account?",
             "No. VS Code's source is MIT licensed and code-server is MIT licensed. No licence, "
                     + "no account, no activation, no payment."},
            {"Editor",
             "What is missing compared to Microsoft's own build?",
             "Only Microsoft's own proprietary parts: their marketplace, the C# debugger, the "
                     + "Windows C++ debugger, Remote-SSH, Dev Containers, WSL and Live Share. "
                     + "None of them apply to a phone that is itself the machine. Open-source "
                     + "alternatives exist for C and C++ (LLVM's clangd) and for C#."},
            {"Editor",
             "What is Open VSX?",
             "The extension registry Code-OSS builds use, run by the Eclipse Foundation. "
                     + "Microsoft's marketplace is limited by its own terms to Microsoft's "
                     + "products, so every non-Microsoft build uses this one — VSCodium, "
                     + "code-server, Cursor, Windsurf, and Google's own Antigravity IDE."},
            {"Editor",
             "Is Open VSX behind Microsoft's marketplace?",
             "Not for these extensions. In September 2026 all three agent extensions carried the "
                     + "same version, published the same day, on both registries. Microsoft's is "
                     + "larger overall, which matters only if you need something that is not on "
                     + "Open VSX."},

            {"Agents",
             "Which agents are set up?",
             "Google's Antigravity, Anthropic's Claude Code and OpenAI's Codex — each the "
                     + "publisher's own extension, from the publisher's own verified namespace."},
            {"Agents",
             "Can I add others?",
             "Yes. Any extension on Open VSX. Verified publishers are browsable by default; "
                     + "unverified ones need a setting turned on, with a warning."},
            {"Agents",
             "Why these three?",
             Agents.WHY_THESE_THREE},
            {"Agents",
             "Which of them are free?",
             "Only Antigravity has a real free tier — Google's Individual plan is $0 and "
                     + "includes several Gemini models, Claude Sonnet and Opus, and gpt-oss, "
                     + "with weekly rate limits. Claude Code needs Pro, Max, Team or Enterprise, "
                     + "or pay-as-you-go. Codex is included in ChatGPT Plus, Pro, Business, Edu "
                     + "and Enterprise."},
            {"Agents",
             "Does my code get uploaded?",
             "The model always runs in the publisher's cloud — that is true of every coding "
                     + "agent anywhere, on any device. The extension sends the context it needs "
                     + "to answer. Your files stay on the phone and edits happen on the phone. "
                     + "What each company does with what it receives is covered by their own "
                     + "privacy policy, not this one."},

            {"Building",
             "Can I build an Android APK?",
             "Yes, once the Android toolchain is installed from Settings. One thing stated "
                     + "plainly: Google ships aapt2 for 64-bit Intel Linux only, so a "
                     + "community-built ARM64 version of that one tool is used. It is labelled "
                     + "as community-built before it is installed."},
            {"Building",
             "Can I build an iOS app without a Mac?",
             "Yes, for cross-platform projects. Write the app here with Flutter, React Native, "
                     + "Expo or Capacitor, push to GitHub, and GitHub's macOS runners build and "
                     + "sign the .ipa. For public repositories those runners are free with no "
                     + "minute limit; for private ones macOS minutes are billed at ten times the "
                     + "rate, so the free quota goes about ten times faster.\n\n"
                     + "What is not possible: compiling a native Xcode Swift or SwiftUI project "
                     + "on the phone. Xcode and codesign only run on macOS, and no trick changes "
                     + "that."},
            {"Building",
             "Can I make games?",
             "Yes. Godot's official Android editor creates, develops and exports 2D and 3D "
                     + "projects, to Android and iOS, and is MIT licensed. Inside PocketIDE an "
                     + "agent can write GDScript and export headlessly. Godot's Android editor "
                     + "does not support C#. Unity has no ARM64 Linux editor, so Unity scripts "
                     + "can be written here and built elsewhere."},
            {"Building",
             "What can I build right now, with no extra set-up?",
             "Websites and web apps, Node and Python backends, APIs, scripts, bots, command-line "
                     + "tools, and anything in Go, Rust, Java, C or C++ — write, compile and run. "
                     + "Git works fully."},

            {"Safety",
             "Are extensions safe?",
             "Extensions are not sandboxed. Visual Studio Code's own documentation says the "
                     + "extension host has the same permissions as the editor itself, which "
                     + "means an extension can read your files, reach the network and run "
                     + "programs. Counterfeit extensions appeared on both registries through "
                     + "2026, and every one of them came from an unverified publisher.\n\n"
                     + "So: verified publishers only by default, the three recommended ones are "
                     + "pinned to a checksum this app verifies before installing, and turning "
                     + "off the filter takes a deliberate act with a warning in front of it."},
            {"Safety",
             "What does the app ask my phone for?",
             "Internet, network state, a wake lock, notifications, a foreground service, "
                     + "vibration, and permission to keep working when the battery saver would "
                     + "stop it. Nothing else. No camera, no microphone, no location, no "
                     + "contacts, no SMS, no storage."},
            {"Safety",
             "Why does it need a wake lock and battery permission?",
             "Set-up downloads for twenty minutes or more. Without them Android sleeps the "
                     + "processor when the screen goes off and the download stops with no "
                     + "explanation. You are asked, never forced, and the app works without "
                     + "them — just less reliably."},
            {"Safety",
             "Can another app on my phone reach the editor?",
             "No. The editor listens on 127.0.0.1 only, and it is behind a password this phone "
                     + "generated for itself. Android does not keep loopback private between "
                     + "apps, which is exactly why that password exists."},

            {"Phone",
             "Which phones work?",
             "Android 10 or newer, 64-bit ARM (arm64-v8a), and enough free space for what you "
                     + "install — about 1.4 GB for the base and the editor, plus each agent."},
            {"Phone",
             "Will my phone get hot?",
             "It gets warm while an agent works, which is normal. The Home screen shows the "
                     + "phone's own thermal state so you can see it rather than guess."},
            {"Phone",
             "What happens if I uninstall?",
             "Everything goes: Ubuntu, the editor, the extensions, your projects and your "
                     + "sign-ins. Nothing is left anywhere else, because nothing was ever "
                     + "anywhere else."},
            {"Phone",
             "Can I stop it?",
             "Yes, from the notification, at any time. Stopping ends the editor and everything "
                     + "running inside Linux. Your files are untouched."},
    };

    // ------------------------------------------------------------------ terms

    static final String TERMS_TITLE = "Terms of use";

    static final String TERMS =
            "1. What this app is.\n"
                    + "PocketIDE is a host. It installs and runs software published by other "
                    + "people: Canonical's Ubuntu, Coder's code-server, Microsoft's Code-OSS, "
                    + "and extensions from the Open VSX registry. It is not affiliated with, "
                    + "endorsed by or sponsored by Microsoft, Google, Anthropic, OpenAI, "
                    + "Canonical, Coder or the Eclipse Foundation. Their names and marks belong "
                    + "to them and are used here only to say truthfully whose software this is.\n\n"

                    + "2. Third-party terms apply.\n"
                    + "Using a coding agent means agreeing to that company's own terms and "
                    + "paying for their own plan. This app does not resell, proxy, subsidise or "
                    + "provide access to any of them.\n\n"

                    + "3. Accounts and payment.\n"
                    + "You sign in to each agent with your own account, inside that publisher's "
                    + "own extension. PocketIDE never asks for, sees or stores those "
                    + "credentials, and no sign-in ever happens in a window this app controls.\n\n"

                    + "4. Extensions are third-party software.\n"
                    + "Extensions run with the same permissions as the editor. Installing an "
                    + "unverified extension is your decision and your risk. Before anything is "
                    + "installed you are shown the publisher, whether the namespace is verified, "
                    + "the version, the size and the checksum.\n\n"

                    + "5. Community components.\n"
                    + "Some optional toolchains are community-maintained rather than published "
                    + "by the vendor — the ARM64 build of Android's aapt2 is the main one. They "
                    + "are labelled as such before installation, never presented as official.\n\n"

                    + "6. No warranty.\n"
                    + "The app is provided as-is, without warranty of any kind. Building, "
                    + "publishing and distributing anything you make is your responsibility, "
                    + "including app-store rules, licences, export rules and the law where you "
                    + "live.\n\n"

                    + "7. What you are responsible for.\n"
                    + "Anything an agent does in your workspace happens because you asked for "
                    + "it. Read what it proposes before you accept it, exactly as you would a "
                    + "change from anyone else.\n\n"

                    + "8. Licence.\n"
                    + "PocketIDE's own code is Apache-2.0. Every bundled component keeps its "
                    + "own licence; see Open-source notices.\n\n"

                    + "9. Changes.\n"
                    + "The terms shown here are the current ones. Anything material is noted in "
                    + "the release notes rather than changed quietly.";

    // ------------------------------------------------------------------ privacy

    static final String PRIVACY_TITLE = "Privacy";

    static final String PRIVACY_SUMMARY =
            "Nothing leaves your phone except what you ask for.";

    static final String PRIVACY =
            "No analytics. No telemetry. No crash reporting to anyone. No account. No server.\n\n"

                    + "There is no PocketIDE account and no PocketIDE server, so there is "
                    + "nowhere for your data to be sent even by accident. Telemetry is switched "
                    + "off in the editor's own settings as well.\n\n"

                    + "Where your files live.\n"
                    + "In this app's private storage. Android keeps that apart from every other "
                    + "app on the phone. Nothing is backed up, synced or copied anywhere.\n\n"

                    + "When the app uses the network.\n"
                    + "Three times, and only these three:\n"
                    + "  • downloading Ubuntu packages from Canonical\n"
                    + "  • downloading the editor and extensions from Coder's releases and the "
                    + "Open VSX registry\n"
                    + "  • each agent talking to its own company, under that company's own "
                    + "privacy policy\n\n"

                    + "Where your sign-ins are kept.\n"
                    + "Inside the Linux workspace, in each publisher's own extension storage. "
                    + "Never in this app's code and never in the installed package.\n\n"

                    + "The editor's password.\n"
                    + "Generated on this phone, kept on this phone, and used only to stop other "
                    + "apps on the same phone from reaching the editor over loopback.\n\n"

                    + "Crash records.\n"
                    + "If the app stops unexpectedly it writes the technical details to a file "
                    + "in its own storage so you can read or share them deliberately. That file "
                    + "is never sent anywhere on its own.\n\n"

                    + "On uninstall.\n"
                    + "Everything is deleted with the app.";

    /** The permission list, checked against AndroidManifest.xml by tests/permissions.py. */
    static final String[][] PERMISSIONS = {
            {"android.permission.INTERNET",
             "Internet", "Downloads, and each agent's own traffic."},
            {"android.permission.ACCESS_NETWORK_STATE",
             "Network state", "Follow DNS when Wi-Fi and mobile data swap, and know when to wait."},
            {"android.permission.WAKE_LOCK",
             "Keep the processor awake", "So a twenty-minute download is not stopped by the screen turning off."},
            {"android.permission.POST_NOTIFICATIONS",
             "Notifications", "The running notice, its progress, and its Stop button."},
            {"android.permission.FOREGROUND_SERVICE",
             "Foreground service", "So Android does not kill the workspace while you are using it."},
            {"android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
             "Foreground service type", "Declares what that service is for, as Android requires."},
            {"android.permission.VIBRATE",
             "Vibration", "Feedback on the key row."},
            {"android.permission.USE_BIOMETRIC",
                    "App lock",
                    "Only to ask the phone to confirm it is you when the app lock is on. The "
                            + "check is the phone's own; no fingerprint or PIN ever reaches "
                            + "this app, and nothing is stored. Granted at install because it "
                            + "is a normal permission, and unused while the lock is off."},
            {"android.permission.MANAGE_EXTERNAL_STORAGE",
                    "The phone's files, if you turn them on",
                    "Off unless you switch it on in Settings, and Android grants it on a page "
                            + "of its own. On, the phone's storage appears inside the workspace "
                            + "as ~/phone so you can open a file from Downloads or save a build "
                            + "somewhere that outlives the app. Nothing is read or copied "
                            + "without you doing it."},
            {"android.permission.READ_EXTERNAL_STORAGE",
                    "The phone's files on Android 10",
                    "The same thing, on the one Android version that predates the page above. "
                            + "Declared with maxSdkVersion 29, so newer phones never see it."},
            {"android.permission.WRITE_EXTERNAL_STORAGE",
                    "Writing to the phone's files on Android 10",
                    "As above, and equally limited to Android 10."},
            {"android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
             "Battery", "A one-tap prompt so long work is not stopped. Always optional."},
    };

    /** Named so the Help screen can say what is deliberately absent. */
    static final String NOT_REQUESTED =
            "Camera, microphone, location, contacts, SMS, calendar, phone, storage and account "
                    + "access are not requested at all. Nothing in this app needs them, and an "
                    + "app that asks for what it does not need is not one to trust with a code "
                    + "repository.";

    // ------------------------------------------------------------------ where it fits

    static final String SURFACES_TITLE = "Where this fits";

    static final String SURFACES =
            "The industry calls these development interfaces, or product surfaces. There are "
                    + "four, and all three companies support all four:\n\n"
                    + "• Agent-first desktop workspace — hand over a project and supervise. "
                    + "Desktop only; a phone can watch it through a remote companion.\n\n"
                    + "• IDE integration — the agent inside the editor, with code, terminal and "
                    + "debugger. Normally desktop only.\n\n"
                    + "• Terminal agent (CLI) — the agent in the project folder's terminal. "
                    + "Normally desktop; a phone can reach it remotely.\n\n"
                    + "• Browser workspace — build and preview on a website. Works on a phone, "
                    + "but what it can do varies.\n\n"
                    + "Two supporting modes exist as well: a mobile remote companion, where the "
                    + "work still runs on a connected computer or in the cloud, and headless "
                    + "automation through an SDK or CI.\n\n"
                    + "PocketIDE's position: it makes the second one — IDE integration, ranked "
                    + "second overall for features and control — run on the phone itself, with "
                    + "no connected computer anywhere. That is the whole point of the app.";
}
